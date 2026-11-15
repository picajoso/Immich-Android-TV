package nl.giejay.android.tv.immich.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DebugPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.ScreensaverPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import nl.giejay.android.tv.immich.shared.prefs.ViewPrefScreen
import nl.giejay.android.tv.immich.ui.FocusRowView
import nl.giejay.android.tv.immich.ui.PageKit
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens

/** One entry of the settings list: title + subtitle on the left, detail on the right when focused. */
data class NewSettingsItem(
    val title: String,
    val description: String,
    val iconName: String,
    val details: () -> List<Pair<String, String>>,
    val actionLabel: String,
    val onClick: () -> Unit
)

/**
 * Settings, two panes: the list of sections on the left, the detail of the focused section on the
 * right. OK opens the section (same destinations as before).
 */
class SettingsFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter<Fragment>(this)

    private lateinit var tokens: UiTokens
    private lateinit var detailTitle: TextView
    private lateinit var detailRows: LinearLayout
    private lateinit var detailAction: TextView
    private var shownItem: NewSettingsItem? = null

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tokens.bg)
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)
        root.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, UiStyleProvider.dp(ctx, 24))

        val title = TextView(ctx)
        title.text = nl.giejay.android.tv.immich.shared.util.Tr.t("Settings")
        UiStyleProvider.applyTitle(title, tokens)
        root.addView(title)

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.HORIZONTAL
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = UiStyleProvider.dp(ctx, 12)
        })

        val list = VerticalGridView(ctx)
        list.id = View.generateViewId()
        list.setNumColumns(1)
        list.clipChildren = false
        list.clipToPadding = false
        list.setPadding(0, UiStyleProvider.dp(ctx, 6), 0, UiStyleProvider.dp(ctx, 12))
        list.setItemSpacing(UiStyleProvider.dp(ctx, 4))
        body.addView(list, LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 320), ViewGroup.LayoutParams.MATCH_PARENT))

        // detail pane
        val pane = LinearLayout(ctx)
        pane.orientation = LinearLayout.VERTICAL
        pane.setPadding(UiStyleProvider.dp(ctx, 28), UiStyleProvider.dp(ctx, 24), UiStyleProvider.dp(ctx, 28), UiStyleProvider.dp(ctx, 24))
        pane.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiStyleProvider.dp(ctx, 14).toFloat()
            setColor(tokens.pillBg)
        }
        detailTitle = TextView(ctx)
        UiStyleProvider.applyTitle(detailTitle, tokens, 22f)
        pane.addView(detailTitle)
        detailRows = LinearLayout(ctx)
        detailRows.orientation = LinearLayout.VERTICAL
        pane.addView(detailRows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 14)
        })
        detailAction = TextView(ctx)
        PageKit.stylePill(detailAction, tokens, selected = true)
        detailAction.isFocusable = false
        pane.addView(detailAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 22)
        })
        body.addView(pane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UiStyleProvider.dp(ctx, 28)
            gravity = Gravity.TOP
        })

        val items = buildItems()
        val adapter = ArrayObjectAdapter(SettingsRowPresenter(tokens) { showDetail(it) })
        adapter.addAll(0, items)
        list.adapter = ItemBridgeAdapter(adapter)
        items.firstOrNull()?.let { showDetail(it) }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
    }

    override fun onResume() {
        super.onResume()
        // values may have changed in the sub screens
        shownItem?.let { showDetail(it, force = true) }
    }

    private fun buildItems(): List<NewSettingsItem> = listOf(
        NewSettingsItem(
            nl.giejay.android.tv.immich.shared.util.Tr.t("Server"), nl.giejay.android.tv.immich.shared.util.Tr.t("Connection to your Immich server"), "ic_settings_settings",
            {
                val host = PreferenceManager.get(HOST_NAME)
                listOf(
                    nl.giejay.android.tv.immich.shared.util.Tr.t("Address") to (if (host.isNotBlank()) host else nl.giejay.android.tv.immich.shared.util.Tr.t("Not set")),
                    nl.giejay.android.tv.immich.shared.util.Tr.t("Status") to (if (PreferenceManager.isLoggedId()) nl.giejay.android.tv.immich.shared.util.Tr.t("Configured") else nl.giejay.android.tv.immich.shared.util.Tr.t("Not signed in"))
                )
            },
            nl.giejay.android.tv.immich.shared.util.Tr.t("Change server")
        ) { findNavController().navigate(HomeFragmentDirections.actionGlobalSignInFragment()) },
        NewSettingsItem(
            nl.giejay.android.tv.immich.shared.util.Tr.t("View settings"), nl.giejay.android.tv.immich.shared.util.Tr.t("Interface style, grid, thumbnails"), "icon_view",
            {
                listOf(
                    nl.giejay.android.tv.immich.shared.util.Tr.t("Interface style") to (when (UiStyleProvider.currentStyle()) {
                        UiStyle.APPLE -> nl.giejay.android.tv.immich.shared.util.Tr.t("Apple")
                        UiStyle.GOOGLE_TV -> nl.giejay.android.tv.immich.shared.util.Tr.t("Google TV")
                    })
                )
            },
            nl.giejay.android.tv.immich.shared.util.Tr.t("Open view settings")
        ) { findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(ViewPrefScreen.key)) },
        NewSettingsItem(
            nl.giejay.android.tv.immich.shared.util.Tr.t("Screensaver"), nl.giejay.android.tv.immich.shared.util.Tr.t("Screensaver options"), "screensaver",
            { listOf(nl.giejay.android.tv.immich.shared.util.Tr.t("Options") to nl.giejay.android.tv.immich.shared.util.Tr.t("Source, interval, metadata")) },
            nl.giejay.android.tv.immich.shared.util.Tr.t("Open screensaver settings")
        ) { findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(ScreensaverPrefScreen.key)) },
        NewSettingsItem(
            nl.giejay.android.tv.immich.shared.util.Tr.t("Debug"), nl.giejay.android.tv.immich.shared.util.Tr.t("Logs and diagnostics"), "bug",
            { listOf(nl.giejay.android.tv.immich.shared.util.Tr.t("Debug mode") to (if (PreferenceManager.get(DEBUG_MODE)) nl.giejay.android.tv.immich.shared.util.Tr.t("On") else nl.giejay.android.tv.immich.shared.util.Tr.t("Off"))) },
            nl.giejay.android.tv.immich.shared.util.Tr.t("Open debug settings")
        ) { findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(DebugPrefScreen.key)) },
        NewSettingsItem(
            nl.giejay.android.tv.immich.shared.util.Tr.t("Donate"), nl.giejay.android.tv.immich.shared.util.Tr.t("Support the project"), "donate",
            { listOf(nl.giejay.android.tv.immich.shared.util.Tr.t("Thanks") to nl.giejay.android.tv.immich.shared.util.Tr.t("This app is free and open source")) },
            nl.giejay.android.tv.immich.shared.util.Tr.t("Open")
        ) { findNavController().navigate(HomeFragmentDirections.actionHomeToDonate()) }
    )

    private fun showDetail(item: NewSettingsItem, force: Boolean = false) {
        if (!force && shownItem == item) return
        shownItem = item
        val ctx = requireContext()
        detailTitle.text = item.title
        detailAction.text = item.actionLabel
        detailRows.removeAllViews()
        item.details().forEach { (label, value) ->
            val line = LinearLayout(ctx)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, UiStyleProvider.dp(ctx, 6), 0, UiStyleProvider.dp(ctx, 6))
            val l = TextView(ctx)
            UiStyleProvider.applyBody(l, tokens, 14f, tokens.dim)
            l.text = label
            val v = TextView(ctx)
            UiStyleProvider.applyBody(v, tokens, 14f, tokens.text)
            v.text = value
            line.addView(l, LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 150), ViewGroup.LayoutParams.WRAP_CONTENT))
            line.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            detailRows.addView(line)
        }
    }

    private class SettingsRowPresenter(
        private val tokens: UiTokens,
        private val onFocused: (NewSettingsItem) -> Unit
    ) : Presenter() {

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val ctx = parent.context
            val row = FocusRowView(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setPadding(UiStyleProvider.dp(ctx, 14), UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 14), UiStyleProvider.dp(ctx, 10))
            row.background = UiStyleProvider.sidebarRowBackground(ctx, tokens)

            val texts = LinearLayout(ctx)
            texts.orientation = LinearLayout.VERTICAL
            val name = TextView(ctx)
            UiStyleProvider.applyTitle(name, tokens, 16f)
            name.setTextColor(UiStyleProvider.sidebarRowTextColors(tokens))
            val sub = TextView(ctx)
            UiStyleProvider.applyBody(sub, tokens, 13f, tokens.dim)
            sub.maxLines = 1
            texts.addView(name)
            texts.addView(sub)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chevron = TextView(ctx)
            chevron.text = "›"
            UiStyleProvider.applyBody(chevron, tokens, 20f, tokens.dim)
            row.addView(chevron)
            row.tag = arrayOf<Any>(name, sub)
            row.setOnFocusChangeListener { v, hasFocus -> v.isSelected = hasFocus }
            return ViewHolder(row)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val entry = item as? NewSettingsItem ?: return
            val row = viewHolder.view as FocusRowView
            @Suppress("UNCHECKED_CAST")
            val parts = row.tag as Array<Any>
            (parts[0] as TextView).text = entry.title
            (parts[1] as TextView).text = entry.description
            row.onFocusGained = { onFocused(entry) }
            row.setOnClickListener { entry.onClick() }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val row = viewHolder.view as FocusRowView
            row.onFocusGained = null
            row.setOnClickListener(null)
        }
    }
}
