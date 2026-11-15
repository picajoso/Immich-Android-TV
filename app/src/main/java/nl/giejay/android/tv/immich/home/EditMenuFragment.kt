package nl.giejay.android.tv.immich.home

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.ui.FocusRowView
import nl.giejay.android.tv.immich.ui.PageKit
import nl.giejay.android.tv.immich.ui.ToggleSwitchView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens

/** Set when the visible side-menu entries changed; the menu rebuilds itself when it is shown again. */
object MenuState {
    @Volatile
    var dirty: Boolean = false
}

/**
 * Edit: choose which sections appear in the side menu. OK on a row turns the section on or off
 * (same preference as before, HIDDEN_HOME_ITEMS). Reordering is not supported.
 */
class EditMenuFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter<Fragment>(this)
    private lateinit var tokens: UiTokens
    private var list: VerticalGridView? = null

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tokens.bg)
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)
        root.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, 0)

        val title = TextView(ctx)
        title.text = nl.giejay.android.tv.immich.shared.util.Tr.t("Edit menu")
        UiStyleProvider.applyTitle(title, tokens)
        root.addView(title)

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.HORIZONTAL
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val grid = VerticalGridView(ctx)
        grid.setNumColumns(1)
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(0, UiStyleProvider.dp(ctx, 14), 0, UiStyleProvider.dp(ctx, 24))
        grid.setItemSpacing(UiStyleProvider.dp(ctx, 4))
        list = grid
        body.addView(grid, LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 360), ViewGroup.LayoutParams.MATCH_PARENT))

        val side = LinearLayout(ctx)
        side.orientation = LinearLayout.VERTICAL
        side.setPadding(UiStyleProvider.dp(ctx, 40), UiStyleProvider.dp(ctx, 14), 0, 0)
        val help = TextView(ctx)
        UiStyleProvider.applyBody(help, tokens, 14f, tokens.dim)
        help.text = nl.giejay.android.tv.immich.shared.util.Tr.t("Choose what appears in the side menu.\nOK turns a section on or off.")
        side.addView(help)
        val reset = PageKit.pill(ctx, tokens, nl.giejay.android.tv.immich.shared.util.Tr.t("Reset to default"), selected = false, focusable = true) {
            PreferenceManager.save(HIDDEN_HOME_ITEMS, emptySet<String>())
            MenuState.dirty = true
            grid.adapter?.notifyDataSetChanged()
        }
        side.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 18)
        })
        body.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
        val adapter = ArrayObjectAdapter(EditRowPresenter(tokens))
        // Edit itself and Settings cannot be hidden
        adapter.addAll(0, HomeFragment.MENU_ENTRIES.filter { it != "Edit" })
        list?.adapter = ItemBridgeAdapter(adapter)
    }
}

private class EditRowPresenter(private val tokens: UiTokens) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val row = FocusRowView(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.setPadding(UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, 8), UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, 8))
        row.background = UiStyleProvider.sidebarRowBackground(ctx, tokens)

        val icon = ImageView(ctx)
        icon.setColorFilter(tokens.dim)
        row.addView(icon, LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 20), UiStyleProvider.dp(ctx, 20)).apply {
            marginEnd = UiStyleProvider.dp(ctx, 12)
        })
        val name = TextView(ctx)
        UiStyleProvider.applyBody(name, tokens, 15f, tokens.text2)
        name.setTextColor(UiStyleProvider.sidebarRowTextColors(tokens))
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = ToggleSwitchView(ctx, tokens)
        row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = arrayOf<Any>(icon, name, toggle)
        // focused row is mirrored as selected so the row background / text colors react
        row.setOnFocusChangeListener { v, hasFocus -> v.isSelected = hasFocus }
        return ViewHolder(row)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val entry = item as? String ?: return
        val row = viewHolder.view as FocusRowView
        @Suppress("UNCHECKED_CAST")
        val parts = row.tag as Array<Any>
        val icon = parts[0] as ImageView
        val name = parts[1] as TextView
        val toggle = parts[2] as ToggleSwitchView

        name.text = nl.giejay.android.tv.immich.shared.util.Tr.t(entry)
        val iconRes = ImmichRowPresenter.ICONS[entry]
        if (iconRes != null) icon.setImageResource(iconRes) else icon.setImageDrawable(null)
        toggle.checked = !PreferenceManager.itemInStringSet(entry, HIDDEN_HOME_ITEMS)
        row.setOnClickListener {
            PreferenceManager.toggleStringSetItem(entry, HIDDEN_HOME_ITEMS)
            MenuState.dirty = true
            toggle.checked = !PreferenceManager.itemInStringSet(entry, HIDDEN_HOME_ITEMS)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.setOnClickListener(null)
    }
}
