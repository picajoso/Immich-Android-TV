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
import nl.giejay.android.tv.immich.ui.UiFormat
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
        fun dp(v: Int) = UiStyleProvider.dp(ctx, v)

        // mockup artboard / 2: padding 56px 72px 56px 64px, 56px between the year column and the months
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.HORIZONTAL
        root.setBackgroundColor(tokens.bg)
        root.setPadding(dp(32), dp(28), dp(36), dp(28))
        root.clipChildren = false
        root.clipToPadding = false

        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        root.addView(left, LinearLayout.LayoutParams(dp(165), ViewGroup.LayoutParams.MATCH_PARENT))

        val title = TextView(ctx)
        title.text = "Timeline"
        title.includeFontPadding = false
        UiStyleProvider.applyTitle(title, tokens, 28f)
        // CSS line-height 1: the text box is as tall as the font size
        val fm = title.paint.fontMetricsInt
        val extra = (fm.descent - fm.ascent) - title.textSize.toInt()
        left.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
            topMargin = -extra / 2
            bottomMargin = dp(17) - (extra - extra / 2)
        })

        // RIGHT leaves the years for the months, LEFT for the menu (see the layout)
        yearsGrid = inflater.inflate(R.layout.timeline_years_grid, left, false) as VerticalGridView
        yearsGrid.setNumColumns(1)
        left.addView(yearsGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        monthsGrid = inflater.inflate(R.layout.timeline_months_grid, root, false) as VerticalGridView
        monthsGrid.setNumColumns(4)
        val gap = dp(11)
        monthsGrid.setHorizontalSpacing(gap)
        monthsGrid.setVerticalSpacing(gap)
        // padding-top 6px; the focused cell grows by ~5dp, so keep room around the grid
        monthsGrid.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.addView(monthsGrid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            // the grid's own 6dp padding is focus room: pull it back out so the cells sit where the mockup puts them
            leftMargin = dp(28) - dp(6)
            rightMargin = -dp(6)
            topMargin = -dp(3)
            bottomMargin = -dp(6)
        })

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
        val gap = UiStyleProvider.dp(requireContext(), 11)
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
    private var yearAdapter: ArrayObjectAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)

        monthPresenter = MonthCellPresenter(tokens, {
            // LEFT on the first column goes back to the selected year
            yearsGrid.selectedPosition = years.indexOf(selectedYear).coerceAtLeast(0)
            yearsGrid.requestFocus()
        }) { cell ->
            cell.timeBucket?.let { openMonth(it) }
        }
        monthAdapter = ArrayObjectAdapter(monthPresenter!!)
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

        val presenter = YearPresenter(tokens, perYear, { selectedYear }, { monthsGrid.requestFocus() }) { year -> selectYear(year) }
        val adapter = ArrayObjectAdapter(presenter)
        adapter.addAll(0, years)
        yearAdapter = adapter
        yearsGrid.adapter = ItemBridgeAdapter(adapter)
        years.firstOrNull()?.let { selectYear(it) }
    }

    private fun selectYear(year: Int) {
        if (selectedYear == year) return
        val previous = selectedYear
        selectedYear = year
        // the selected year stays highlighted while the focus is in the months grid
        yearAdapter?.let { a ->
            listOfNotNull(previous, year).forEach { y ->
                val idx = years.indexOf(y)
                if (idx >= 0) a.notifyArrayItemRangeChanged(idx, 1)
            }
        }
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
    private val selectedYear: () -> Int?,
    private val onRight: () -> Unit,
    private val onFocused: (Int) -> Unit
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val row = FocusRowView(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val year = TextView(ctx)
        year.includeFontPadding = false
        val count = TextView(ctx)
        count.includeFontPadding = false
        UiStyleProvider.applyBody(count, tokens, 13f, tokens.dim)
        row.addView(year, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = arrayOf<Any>(year, count)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val y = item as? Int ?: return
        val row = viewHolder.view as FocusRowView
        val ctx = row.context
        @Suppress("UNCHECKED_CAST")
        val parts = row.tag as Array<Any>
        val year = parts[0] as TextView
        year.text = y.toString()
        (parts[1] as TextView).text = UiFormat.count(counts[y] ?: 0)

        // mockup: the selected year is a filled row with a big bold year, the others are plain
        val selected = selectedYear() == y
        val google = tokens.style == nl.giejay.android.tv.immich.shared.prefs.UiStyle.GOOGLE_TV
        val countView = parts[1] as TextView
        val padV = if (selected) 8 else 6
        row.setPadding(UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, padV), UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, padV))
        // Apple: translucent filled row; Google TV: a white pill with dark text
        row.background = if (selected) GradientDrawable().apply {
            setColor(tokens.selectedRow)
            cornerRadius = UiStyleProvider.dp(ctx, if (google) 500 else 8).toFloat()
        } else null
        if (selected) {
            year.textSize = 22f
            year.typeface = UiStyleProvider.titleTypeface(tokens)
            year.setTextColor(if (google) tokens.selectedRowText else tokens.text)
            countView.setTextColor(if (google) (tokens.selectedRowText and 0x00FFFFFF) or 0xBF000000.toInt() else tokens.dim)
        } else {
            year.textSize = 17f
            year.typeface = UiStyleProvider.bodyTypeface(tokens)
            year.setTextColor(if (google) tokens.dim else tokens.text2)
            countView.setTextColor(tokens.dim)
        }
        row.onFocusGained = { onFocused(y) }
        // the single-column grid does not let the focus out sideways: hand it to the months
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && event.action == android.view.KeyEvent.ACTION_DOWN) {
                onRight()
                true
            } else false
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as FocusRowView).onFocusGained = null
        viewHolder.view.setOnKeyListener(null)
    }
}

private class MonthCellPresenter(
    private val tokens: UiTokens,
    private val onLeftEdge: () -> Unit,
    private val onClicked: (MonthCell) -> Unit
) : Presenter() {
    var cellW = 0
    var cellH = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val google = tokens.style == nl.giejay.android.tv.immich.shared.prefs.UiStyle.GOOGLE_TV
        // mockup corners: Apple 18px, Google TV 16px; focus scale 1.07 / 1.08
        val card = FocusCardView(ctx, tokens, focusScale = tokens.focusScale.takeIf { google } ?: 1.07f, radiusDp = if (google) 8f else 9f)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // linear-gradient(transparent 40%, rgba(0,0,0,.72)) over the whole cover
        val scrim = View(ctx)
        scrim.background = android.graphics.drawable.PaintDrawable().apply {
            shape = android.graphics.drawable.shapes.RectShape()
            shaderFactory = object : android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): android.graphics.Shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x00000000, 0x00000000, 0xB8000000.toInt()),
                    floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP
                )
            }
        }
        card.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // name (bold 14sp) and count (12sp) on one baseline, 11dp from the sides, 8dp from the bottom
        val labels = LinearLayout(ctx)
        labels.orientation = LinearLayout.HORIZONTAL
        labels.setPadding(UiStyleProvider.dp(ctx, 11), 0, UiStyleProvider.dp(ctx, 11), UiStyleProvider.dp(ctx, 8))
        val name = TextView(ctx)
        name.includeFontPadding = false
        UiStyleProvider.applyTitle(name, tokens, 14f)
        name.letterSpacing = 0f
        name.setTextColor(0xFFFFFFFF.toInt())
        val count = TextView(ctx)
        count.includeFontPadding = false
        UiStyleProvider.applyBody(count, tokens, 12f, 0xD9FFFFFF.toInt())
        labels.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        labels.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(labels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // empty month: "December" with "No photos yet" under it
        val emptyNote = TextView(ctx)
        emptyNote.includeFontPadding = false
        emptyNote.text = "No photos yet"
        UiStyleProvider.applyBody(emptyNote, tokens, 11f, tokens.dim)
        card.addView(emptyNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = UiStyleProvider.dp(ctx, 11)
            bottomMargin = UiStyleProvider.dp(ctx, 8)
        })
        card.tag = arrayOf<Any>(image, scrim, name, count, emptyNote, labels)
        return ViewHolder(card)
    }

    private fun monthName(month: Int): String {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2000, month - 1, 1)
        val f = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMM"), locale)
        f.timeZone = TimeZone.getTimeZone("UTC")
        // "enero" -> "Enero" like the mockup's capitalised month names
        return f.format(cal.time).replaceFirstChar { it.titlecase(locale) }
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
        val emptyNote = parts[4] as TextView
        val labels = parts[5] as LinearLayout
        val ctx = card.context

        if (cellW > 0 && cellH > 0) {
            card.layoutParams = ViewGroup.MarginLayoutParams(cellW, cellH)
        } else if (card.layoutParams == null) {
            card.layoutParams = ViewGroup.MarginLayoutParams(UiStyleProvider.dp(ctx, 120), UiStyleProvider.dp(ctx, 80))
        }
        name.text = monthName(cell.month)
        val radius = UiStyleProvider.dp(ctx, if (tokens.style == nl.giejay.android.tv.immich.shared.prefs.UiStyle.GOOGLE_TV) 8 else 9).toFloat()
        if (cell.count > 0) {
            count.text = UiFormat.count(cell.count)
            count.visibility = View.VISIBLE
            emptyNote.visibility = View.GONE
            name.setTextColor(0xFFFFFFFF.toInt())
            name.textSize = 14f
            labels.setPadding(UiStyleProvider.dp(ctx, 11), 0, UiStyleProvider.dp(ctx, 11), UiStyleProvider.dp(ctx, 8))
            card.isFocusable = true
            card.alpha = 1f
            card.background = GradientDrawable().apply { setColor(tokens.pillBg); cornerRadius = radius }
            scrim.visibility = View.VISIBLE
            val id = cell.coverAssetId
            val url = id?.let { ApiUtil.getThumbnailUrl(it, "preview") }
            if (url != null) Glide.with(image).asBitmap().load(url).centerCrop().into(image) else image.setImageDrawable(null)
            card.setOnClickListener { onClicked(cell) }
            card.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (cell.month - 1) % 4 == 0
                ) {
                    onLeftEdge()
                    true
                } else false
            }
        } else {
            // empty month: dashed outline, name + "No photos yet", not focusable
            count.visibility = View.GONE
            emptyNote.visibility = View.VISIBLE
            name.setTextColor(tokens.dim)
            // lift the name above the note
            labels.setPadding(UiStyleProvider.dp(ctx, 11), 0, UiStyleProvider.dp(ctx, 11), UiStyleProvider.dp(ctx, 8) + UiStyleProvider.dp(ctx, 14))
            card.isFocusable = false
            card.alpha = 1f
            card.background = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = radius
                setStroke(UiStyleProvider.dp(ctx, 1f), 0x1AFFFFFF, UiStyleProvider.dp(ctx, 4f).toFloat(), UiStyleProvider.dp(ctx, 4f).toFloat())
            }
            scrim.visibility = View.GONE
            image.setImageDrawable(null)
            card.setOnClickListener(null)
            card.setOnKeyListener(null)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as FocusCardView
        card.resetFocusState()
        card.setOnClickListener(null)
        card.setOnKeyListener(null)
        @Suppress("UNCHECKED_CAST")
        val parts = card.tag as Array<Any>
        try {
            Glide.with(parts[0] as ImageView).clear(parts[0] as ImageView)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
    }
}
