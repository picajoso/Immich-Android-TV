package nl.giejay.android.tv.immich.assets

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
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
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.ui.FocusCardView
import nl.giejay.android.tv.immich.ui.FocusRowView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One month cell of the overview. [timeBucket] is null for months without photos. */
data class MonthCell(
    val year: Int,
    val month: Int,
    val count: Int,
    val timeBucket: String?,
    var coverAssetId: String? = null
)

/**
 * Timeline overview: years with photo counts on the left, a 4 x 3 grid with the 12 months of the
 * selected year on the right. Each month shows ONE cover (first asset of the month) and its count;
 * empty months are dashed and dimmed. Counts come from the time buckets API; covers are loaded one
 * request per month of the selected year only (never whole months).
 */
class TimelineOverviewFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    private lateinit var tokens: UiTokens
    private lateinit var yearsGrid: VerticalGridView
    private lateinit var monthsGrid: VerticalGridView
    private lateinit var monthAdapter: ArrayObjectAdapter

    private var buckets: List<Bucket> = emptyList()
    private var years: List<Int> = emptyList()
    private var selectedYear: Int? = null
    private val covers = mutableMapOf<String, String>()

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<TimelineOverviewFragment> {
        return mMainFragmentAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tokens.bg)

        val title = TextView(ctx)
        title.text = "Timeline"
        UiStyleProvider.applyTitle(title, tokens)
        title.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, UiStyleProvider.dp(ctx, 15))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.HORIZONTAL
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        yearsGrid = VerticalGridView(ctx)
        yearsGrid.setNumColumns(1)
        yearsGrid.setPadding(padH, UiStyleProvider.dp(ctx, 4), UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, 24))
        yearsGrid.clipToPadding = false
        body.addView(yearsGrid, LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 150), ViewGroup.LayoutParams.MATCH_PARENT))

        monthsGrid = VerticalGridView(ctx)
        monthsGrid.setNumColumns(4)
        val gap = UiStyleProvider.dp(ctx, tokens.gridGapDp * 4)
        monthsGrid.setHorizontalSpacing(gap)
        monthsGrid.setVerticalSpacing(gap)
        monthsGrid.setPadding(UiStyleProvider.dp(ctx, 8), UiStyleProvider.dp(ctx, 10), padH, UiStyleProvider.dp(ctx, 16))
        monthsGrid.clipToPadding = false
        monthsGrid.clipChildren = false
        body.addView(monthsGrid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // size the month cells so that 4 x 3 exactly fill the right pane
        monthsGrid.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { sizeMonthCells() }
        }
        return root
    }

    private var cellW = 0
    private var cellH = 0

    private fun sizeMonthCells() {
        if (!isAdded || monthsGrid.width <= 0 || monthsGrid.height <= 0) return
        val gap = UiStyleProvider.dp(requireContext(), tokens.gridGapDp * 4)
        val w = (monthsGrid.width - monthsGrid.paddingLeft - monthsGrid.paddingRight - gap * 3) / 4
        val h = (monthsGrid.height - monthsGrid.paddingTop - monthsGrid.paddingBottom - gap * 2) / 3
        if (w != cellW || h != cellH) {
            cellW = w
            cellH = h
            monthPresenter?.let {
                it.cellW = w
                it.cellH = h
            }
            if (this::monthAdapter.isInitialized && monthAdapter.size() > 0) {
                monthAdapter.notifyArrayItemRangeChanged(0, monthAdapter.size())
            }
        }
    }

    private var monthPresenter: MonthCellPresenter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)

        monthPresenter = MonthCellPresenter(tokens) { cell ->
            cell.timeBucket?.let { openMonth(it) }
        }
        monthAdapter = ArrayObjectAdapter(monthPresenter)
        monthsGrid.adapter = ItemBridgeAdapter(monthAdapter)
        loadBuckets()
    }

    private fun openMonth(timeBucket: String) {
        findNavController().navigate(R.id.photoGridFragment, bundleOf("timeBucket" to timeBucket))
    }

    private fun loadBuckets() {
        val client = currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.listBuckets("", PhotosOrder.NEWEST_OLDEST) }
            result.fold(
                { Timber.e("Could not load buckets: $it") },
                { list ->
                    buckets = list
                    showYears()
                }
            )
        }
    }

    private fun parseYearMonth(timeBucket: String): Pair<Int, Int>? {
        // "2026-11-01T00:00:00.000Z" -> (2026, 11)
        if (timeBucket.length < 7) return null
        val y = timeBucket.substring(0, 4).toIntOrNull() ?: return null
        val m = timeBucket.substring(5, 7).toIntOrNull() ?: return null
        return y to m
    }

    private fun showYears() {
        if (!isAdded || view == null) return
        val perYear = buckets.mapNotNull { b -> parseYearMonth(b.timeBucket)?.let { it.first to b.count } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
        years = perYear.keys.sortedDescending()

        val presenter = YearPresenter(tokens, perYear) { year -> selectYear(year) }
        val adapter = ArrayObjectAdapter(presenter)
        adapter.addAll(0, years)
        yearsGrid.adapter = ItemBridgeAdapter(adapter)
        years.firstOrNull()?.let { selectYear(it) }
    }

    private fun selectYear(year: Int) {
        if (selectedYear == year) return
        selectedYear = year
        val byMonth = buckets.mapNotNull { b -> parseYearMonth(b.timeBucket)?.let { Triple(it.first, it.second, b) } }
            .filter { it.first == year }
            .associateBy({ it.second }, { it.third })
        val cells = (1..12).map { m ->
            val b = byMonth[m]
            MonthCell(year, m, b?.count ?: 0, b?.timeBucket, covers["$year-$m"])
        }
        monthAdapter.clear()
        monthAdapter.addAll(0, cells)
        loadCovers(year, cells)
    }

    private fun loadCovers(year: Int, cells: List<MonthCell>) {
        val client = currentApiClient() ?: return
        cells.filter { it.count > 0 && it.coverAssetId == null }.forEach { cell ->
            viewLifecycleOwner.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { client.firstAssetOfMonth(cell.year, cell.month) }
                val id = res.getOrNull()?.id ?: return@launch
                covers["${cell.year}-${cell.month}"] = id
                cell.coverAssetId = id
                if (selectedYear == year && this@TimelineOverviewFragment::monthAdapter.isInitialized) {
                    val idx = monthAdapter.indexOf(cell)
                    if (idx >= 0) monthAdapter.notifyArrayItemRangeChanged(idx, 1)
                }
            }
        }
    }
}

private class YearPresenter(
    private val tokens: UiTokens,
    private val counts: Map<Int, Int>,
    private val onFocused: (Int) -> Unit
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val row = FocusRowView(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.setPadding(UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 7), UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 7))
        row.background = UiStyleProvider.sidebarRowBackground(ctx, tokens)
        val year = TextView(ctx)
        year.textSize = 16f
        year.typeface = UiStyleProvider.titleTypeface(tokens)
        year.setTextColor(UiStyleProvider.sidebarRowTextColors(tokens))
        val count = TextView(ctx)
        UiStyleProvider.applyBody(count, tokens, 13f, tokens.dim)
        row.addView(year, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = arrayOf<Any>(year, count)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val y = item as? Int ?: return
        val row = viewHolder.view as FocusRowView
        @Suppress("UNCHECKED_CAST")
        val parts = row.tag as Array<Any>
        (parts[0] as TextView).text = y.toString()
        (parts[1] as TextView).text = (counts[y] ?: 0).toString()
        row.onFocusGained = { onFocused(y) }
        // the focused row is also the "selected" one, mirrored by state_selected in the background
        row.setOnFocusChangeListener { v, hasFocus -> v.isSelected = hasFocus }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as FocusRowView).onFocusGained = null
    }
}

private class MonthCellPresenter(
    private val tokens: UiTokens,
    private val onClicked: (MonthCell) -> Unit
) : Presenter() {
    var cellW = 0
    var cellH = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = FocusCardView(ctx, tokens)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // bottom scrim so the labels stay readable on any cover
        val scrim = View(ctx)
        scrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        card.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiStyleProvider.dp(ctx, 50)).apply {
            gravity = Gravity.BOTTOM
        })

        val labels = LinearLayout(ctx)
        labels.orientation = LinearLayout.HORIZONTAL
        labels.gravity = Gravity.BOTTOM
        labels.setPadding(UiStyleProvider.dp(ctx, 10), 0, UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 8))
        val name = TextView(ctx)
        UiStyleProvider.applyTitle(name, tokens, 14f)
        name.setTextColor(0xFFFFFFFF.toInt())
        val count = TextView(ctx)
        UiStyleProvider.applyBody(count, tokens, 13f, 0xCCFFFFFF.toInt())
        labels.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        labels.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(labels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        card.tag = arrayOf<Any>(image, scrim, name, count)
        return ViewHolder(card)
    }

    private fun monthName(month: Int): String {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2000, month - 1, 1)
        val f = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMM"), locale)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(cal.time)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val cell = item as? MonthCell ?: return
        val card = viewHolder.view as FocusCardView
        @Suppress("UNCHECKED_CAST")
        val parts = card.tag as Array<Any>
        val image = parts[0] as ImageView
        val scrim = parts[1] as View
        val name = parts[2] as TextView
        val count = parts[3] as TextView
        val ctx = card.context

        if (cellW > 0 && cellH > 0) {
            card.layoutParams = ViewGroup.MarginLayoutParams(cellW, cellH)
        } else if (card.layoutParams == null) {
            card.layoutParams = ViewGroup.MarginLayoutParams(UiStyleProvider.dp(ctx, 120), UiStyleProvider.dp(ctx, 80))
        }
        name.text = monthName(cell.month)
        val radius = UiStyleProvider.dp(ctx, tokens.tileRadiusDp).toFloat()
        if (cell.count > 0) {
            count.text = cell.count.toString()
            card.isFocusable = true
            card.alpha = 1f
            card.background = GradientDrawable().apply { setColor(tokens.pillBg); cornerRadius = radius }
            scrim.visibility = View.VISIBLE
            val id = cell.coverAssetId
            val url = id?.let { ApiUtil.getThumbnailUrl(it, "preview") }
            if (url != null) Glide.with(image).asBitmap().load(url).centerCrop().into(image) else image.setImageDrawable(null)
            card.setOnClickListener { onClicked(cell) }
        } else {
            // empty month: dashed outline, dimmed, not focusable
            count.text = ""
            card.isFocusable = false
            card.alpha = 0.45f
            card.background = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = radius
                setStroke(UiStyleProvider.dp(ctx, 1f), tokens.dim, UiStyleProvider.dp(ctx, 4f).toFloat(), UiStyleProvider.dp(ctx, 4f).toFloat())
            }
            scrim.visibility = View.GONE
            image.setImageDrawable(null)
            card.setOnClickListener(null)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as FocusCardView
        card.resetFocusState()
        card.setOnClickListener(null)
        @Suppress("UNCHECKED_CAST")
        val parts = card.tag as Array<Any>
        try {
            Glide.with(parts[0] as ImageView).clear(parts[0] as ImageView)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
    }
}
