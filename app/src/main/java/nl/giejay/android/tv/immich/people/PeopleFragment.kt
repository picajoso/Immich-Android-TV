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
    private lateinit var peopleCount: TextView

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tokens.bg)
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)

        // title on the left, "24 people" on the right (dim)
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, 0)
        val title = TextView(ctx)
        title.text = "People"
        title.includeFontPadding = false
        UiStyleProvider.applyTitle(title, tokens)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        peopleCount = TextView(ctx)
        peopleCount.includeFontPadding = false
        UiStyleProvider.applyBody(peopleCount, tokens, 13f, tokens.dim)
        header.addView(peopleCount)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid = VerticalGridView(ctx)
        grid.setNumColumns(COLUMNS)
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(padH, UiStyleProvider.dp(ctx, 14), padH, UiStyleProvider.dp(ctx, 24))
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
        peopleCount.text = "${people.size} people"
        val client = currentApiClient()
        val counts = mutableMapOf<java.util.UUID, Int>()
        val asked = mutableSetOf<java.util.UUID>()
        val p = PersonCardPresenter(tokens, counts, { person, done ->
            // photo count per person, asked for the visible cards only
            if (client != null && asked.add(person.id)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { client.personStatistics(person.id.toString()) }
                    res.getOrNull()?.let { counts[person.id] = it.assets; done(it.assets) }
                }
            }
        }) { person ->
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
    private val counts: Map<java.util.UUID, Int>,
    private val loadCount: (Person, (Int) -> Unit) -> Unit,
    private val onClicked: (Person) -> Unit
) : Presenter() {
    var cellW = 0
    var cellH = 0

    private class Parts(val cover: FocusCardView, val image: ImageView, val name: TextView, val count: TextView) {
        var personId: java.util.UUID? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        fun dp(v: Int) = UiStyleProvider.dp(ctx, v)
        val item = LinearLayout(ctx)
        item.orientation = LinearLayout.VERTICAL
        item.clipChildren = false
        item.layoutParams = ViewGroup.MarginLayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)

        val cover = FocusCardView(ctx, tokens)
        cover.setBackgroundColor(tokens.pillBg)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        item.addView(cover, LinearLayout.LayoutParams(dp(150), dp(112)))

        // mockup: name 26px medium, 14px under the card; count 24px dim
        val name = TextView(ctx)
        name.includeFontPadding = false
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        UiStyleProvider.applyTitle(name, tokens, 13f)
        name.letterSpacing = 0f
        item.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        val count = TextView(ctx)
        count.includeFontPadding = false
        count.maxLines = 1
        UiStyleProvider.applyBody(count, tokens, 12f, tokens.dim)
        item.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })

        // the focused card grows: the texts follow it
        cover.setOnFocusChangeListener { _, focused ->
            val shift = if (focused) dp(4).toFloat() else 0f
            name.animate().translationY(shift).setDuration(120).start()
            count.animate().translationY(shift).setDuration(120).start()
        }
        item.tag = Parts(cover, image, name, count)
        return ViewHolder(item)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val person = item as? Person ?: return
        val p = viewHolder.view.tag as Parts
        if (cellW > 0) {
            fun fix(v: View, w: Int, h: Int) {
                val lp = v.layoutParams
                if (lp.width != w || (h > 0 && lp.height != h)) {
                    lp.width = w
                    if (h > 0) lp.height = h
                    v.layoutParams = lp
                }
            }
            fix(viewHolder.view, cellW, 0)
            fix(p.cover, cellW, cellH)
        }
        p.personId = person.id
        val named = !person.name.isNullOrBlank()
        p.name.text = if (named) person.name else "Add a name"
        p.name.setTextColor(if (named) tokens.text else tokens.accent)
        val known = counts[person.id]
        p.count.text = if (known != null) photos(known) else ""
        if (known == null) {
            loadCount(person) { n ->
                // the view may have been recycled for another person meanwhile
                if (p.personId == person.id) p.count.text = photos(n)
            }
        }
        Glide.with(p.image).load(ApiUtil.getPersonThumbnail(person.id)).centerCrop().into(p.image)
        p.cover.setOnClickListener { onClicked(person) }
    }

    private fun photos(n: Int) = "${nl.giejay.android.tv.immich.ui.UiFormat.count(n)} ${if (n == 1) "photo" else "photos"}"

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val p = viewHolder.view.tag as Parts
        p.cover.resetFocusState()
        p.cover.setOnClickListener(null)
        p.personId = null
        p.name.translationY = 0f
        p.count.translationY = 0f
        try {
            Glide.with(p.image).clear(p.image)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
    }
}
