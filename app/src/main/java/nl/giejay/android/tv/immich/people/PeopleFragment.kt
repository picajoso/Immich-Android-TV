package nl.giejay.android.tv.immich.people

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.ui.FocusCardView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber

/**
 * People: 4:3 cards, six per row, name under a bottom scrim ("Add a name" for unnamed people).
 * The picture is the person thumbnail (center-cropped to 4:3).
 */
class PeopleFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter<Fragment>(this)

    private lateinit var tokens: UiTokens
    private lateinit var grid: VerticalGridView
    private var presenter: PersonCardPresenter? = null
    private var adapter: ArrayObjectAdapter? = null
    private var lastWidth = -1

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tokens.bg)
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)

        val title = TextView(ctx)
        title.text = "People"
        UiStyleProvider.applyTitle(title, tokens)
        title.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, UiStyleProvider.dp(ctx, 10))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid = VerticalGridView(ctx)
        grid.setNumColumns(COLUMNS)
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(padH, UiStyleProvider.dp(ctx, 12), padH, UiStyleProvider.dp(ctx, 24))
        grid.setHorizontalSpacing(UiStyleProvider.dp(ctx, GAP_DP))
        grid.setVerticalSpacing(UiStyleProvider.dp(ctx, GAP_DP))
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // six cards per row whatever the width is (menu shown or hidden)
        grid.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width != lastWidth) {
                lastWidth = width
                v.post { sizeCards() }
            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
        load()
    }

    private fun sizeCards() {
        if (!isAdded || grid.width <= 0) return
        val gap = UiStyleProvider.dp(requireContext(), GAP_DP)
        val cellW = (grid.width - grid.paddingLeft - grid.paddingRight - gap * (COLUMNS - 1)) / COLUMNS
        val cellH = cellW * 3 / 4
        val p = presenter ?: return
        if (p.cellW != cellW || p.cellH != cellH) {
            p.cellW = cellW
            p.cellH = cellH
            val a = adapter
            if (a != null && a.size() > 0) a.notifyArrayItemRangeChanged(0, a.size())
        }
    }

    private fun load() {
        val client = currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.listPeople() }
            result.fold(
                { Timber.e("Could not load people: $it") },
                { people -> showPeople(people) }
            )
        }
    }

    private fun showPeople(people: List<Person>) {
        if (!isAdded || view == null) return
        val p = PersonCardPresenter(tokens) { person ->
            findNavController().navigate(
                HomeFragmentDirections.actionHomeFragmentToPersonAssetsFragment(
                    person.id.toString(),
                    person.name?.takeIf { it.isNotBlank() } ?: "Unknown"
                )
            )
        }
        presenter = p
        val a = ArrayObjectAdapter(p)
        // named people first, the server order is kept inside each group
        a.addAll(0, people.sortedBy { it.name.isNullOrBlank() })
        adapter = a
        grid.adapter = ItemBridgeAdapter(a)
        grid.post { sizeCards() }
    }

    companion object {
        private const val COLUMNS = 6
        private const val GAP_DP = 12
    }
}

private class PersonCardPresenter(
    private val tokens: UiTokens,
    private val onClicked: (Person) -> Unit
) : Presenter() {
    var cellW = 0
    var cellH = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = FocusCardView(ctx, tokens)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scrim = View(ctx)
        scrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        card.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiStyleProvider.dp(ctx, 56)).apply {
            gravity = Gravity.BOTTOM
        })

        val name = TextView(ctx)
        UiStyleProvider.applyTitle(name, tokens, 15f)
        name.setTextColor(0xFFFFFFFF.toInt())
        name.maxLines = 1
        name.setPadding(UiStyleProvider.dp(ctx, 10), 0, UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 8))
        card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        card.tag = arrayOf<Any>(image, name)
        card.layoutParams = ViewGroup.MarginLayoutParams(UiStyleProvider.dp(ctx, 150), UiStyleProvider.dp(ctx, 112))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val person = item as? Person ?: return
        val card = viewHolder.view as FocusCardView
        @Suppress("UNCHECKED_CAST")
        val parts = card.tag as Array<Any>
        val image = parts[0] as ImageView
        val name = parts[1] as TextView

        if (cellW > 0) {
            val lp = card.layoutParams
            if (lp.width != cellW || lp.height != cellH) {
                lp.width = cellW
                lp.height = cellH
                card.layoutParams = lp
            }
        }
        val named = !person.name.isNullOrBlank()
        name.text = if (named) person.name else "Add a name"
        name.setTypeface(name.typeface, if (named) Typeface.BOLD else Typeface.ITALIC)
        name.alpha = if (named) 1f else 0.75f
        Glide.with(image).load(ApiUtil.getPersonThumbnail(person.id)).centerCrop().into(image)
        card.setOnClickListener { onClicked(person) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as FocusCardView
        card.resetFocusState()
        card.setOnClickListener(null)
        @Suppress("UNCHECKED_CAST")
        val image = (card.tag as Array<Any>)[0] as ImageView
        try {
            Glide.with(image).clear(image)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
    }
}
