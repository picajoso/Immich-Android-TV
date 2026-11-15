package nl.giejay.android.tv.immich.assets

import android.graphics.Color
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
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.PhotoTilePresenter
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.shared.util.openSliderFor
import nl.giejay.android.tv.immich.shared.util.toCard
import nl.giejay.android.tv.immich.ui.PageKit
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber
import java.util.Calendar
import java.util.Date

/** One horizontal row of photos with a header line (e.g. one year). */
data class PhotoRow(val title: CharSequence, val assets: List<Asset>)

/**
 * Page made of one horizontal row of photos per group (year). Used by On this day and Seasonal.
 * Subclasses provide the rows; optionally a faded hero photo in the top-right corner and
 * display-only chips under the title.
 */
abstract class PhotoRowsFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter<Fragment>(this)

    protected lateinit var tokens: UiTokens
    private lateinit var heroBox: FrameLayout
    private lateinit var heroImage: ImageView
    private lateinit var subtitleView: TextView
    private lateinit var chipsBar: LinearLayout
    private lateinit var rowsGrid: VerticalGridView
    private lateinit var emptyView: TextView
    private var allAssets: List<Asset> = emptyList()

    abstract val pageTitle: String
    protected open val showHero: Boolean get() = false
    protected open val emptyText: String get() = nl.giejay.android.tv.immich.shared.util.Tr.t("No photos found")
    protected open fun subtitle(): String? = null

    /** Chips shown (selected, display only) under the title. */
    protected open fun chips(): List<String> = emptyList()

    abstract suspend fun loadRows(client: ApiClient): List<PhotoRow>

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val root = FrameLayout(ctx)
        root.setBackgroundColor(tokens.bg)

        // faded hero photo in the top-right corner
        heroBox = FrameLayout(ctx)
        heroBox.visibility = View.GONE
        heroImage = ImageView(ctx)
        heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
        heroBox.addView(heroImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun fade(orientation: GradientDrawable.Orientation, vararg colors: Int) {
            val v = View(ctx)
            v.background = GradientDrawable(orientation, colors)
            heroBox.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fun bgAlpha(a: Float) = (tokens.bg and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        fade(GradientDrawable.Orientation.LEFT_RIGHT, bgAlpha(1f), bgAlpha(0.6f), Color.TRANSPARENT)
        fade(GradientDrawable.Orientation.BOTTOM_TOP, bgAlpha(1f), Color.TRANSPARENT)
        heroBox.alpha = 0.55f
        root.addView(heroBox, FrameLayout.LayoutParams(UiStyleProvider.dp(ctx, 520), UiStyleProvider.dp(ctx, 300)).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, 0)

        val title = TextView(ctx)
        title.text = pageTitle
        UiStyleProvider.applyTitle(title, tokens)
        column.addView(title)

        subtitleView = TextView(ctx)
        UiStyleProvider.applyBody(subtitleView, tokens, 14f, tokens.dim)
        subtitleView.visibility = View.GONE
        column.addView(subtitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 2)
        })

        chipsBar = LinearLayout(ctx)
        chipsBar.orientation = LinearLayout.HORIZONTAL
        chipsBar.visibility = View.GONE
        column.addView(chipsBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 10)
        })

        rowsGrid = VerticalGridView(ctx)
        rowsGrid.clipChildren = false
        rowsGrid.clipToPadding = false
        rowsGrid.setPadding(0, UiStyleProvider.dp(ctx, 12), 0, UiStyleProvider.dp(ctx, 24))
        rowsGrid.setItemSpacing(UiStyleProvider.dp(ctx, 6))
        column.addView(rowsGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyView = TextView(ctx)
        UiStyleProvider.applyBody(emptyView, tokens, 16f, tokens.dim)
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = View.GONE
        root.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
        subtitle()?.let {
            subtitleView.text = it
            subtitleView.visibility = View.VISIBLE
        }
        val chipTexts = chips()
        if (chipTexts.isNotEmpty()) {
            chipTexts.forEach { text ->
                val chip = PageKit.pill(requireContext(), tokens, text, selected = true, focusable = false)
                chipsBar.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = UiStyleProvider.dp(requireContext(), 8)
                })
            }
            chipsBar.visibility = View.VISIBLE
        }
        load()
    }

    private fun load() {
        val client = currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { loadRows(client) }
            } catch (e: Exception) {
                Timber.e(e, "Could not load ${javaClass.simpleName}")
                emptyList()
            }
            if (!isAdded || view == null) return@launch
            showRows(rows)
        }
    }

    private fun showRows(rows: List<PhotoRow>) {
        if (rows.isEmpty()) {
            emptyView.text = emptyText
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE
        allAssets = rows.flatMap { it.assets }
        val ctx = requireContext()
        val presenter = PhotoRowPresenter(tokens, UiStyleProvider.dp(ctx, 112)) { card ->
            openSliderFor(allAssets, card.id)
        }
        val adapter = ArrayObjectAdapter(presenter)
        adapter.addAll(0, rows)
        rowsGrid.adapter = ItemBridgeAdapter(adapter)

        val heroAsset = rows.firstOrNull()?.assets?.firstOrNull()
        val heroUrl = if (showHero) ApiUtil.getThumbnailUrl(heroAsset?.id, "preview") else null
        if (heroUrl != null) {
            heroBox.visibility = View.VISIBLE
            Glide.with(this).load(heroUrl).centerCrop().into(heroImage)
        }
    }

    protected fun dateOf(asset: Asset): Date? = asset.exifInfo?.dateTimeOriginal ?: asset.fileModifiedAt

    protected fun yearOf(asset: Asset): Int? {
        val date = dateOf(asset) ?: return null
        val cal = Calendar.getInstance()
        cal.time = date
        return cal.get(Calendar.YEAR)
    }

    /** The most frequent cities of [assets], at most [max], most frequent first. */
    protected fun topCities(assets: List<Asset>, max: Int): List<String> =
        assets.mapNotNull { it.exifInfo?.city?.takeIf { c -> c.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key }
}

private class PhotoRowViewHolder(view: View, val title: TextView, val grid: HorizontalGridView) : Presenter.ViewHolder(view)

private class PhotoRowPresenter(
    private val tokens: UiTokens,
    private val tileSizePx: Int,
    private val onClicked: (Card) -> Unit
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        column.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val title = TextView(ctx)
        UiStyleProvider.applyBody(title, tokens, 14f, tokens.dim)
        title.maxLines = 1
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = HorizontalGridView(ctx)
        grid.setItemSpacing(UiStyleProvider.dp(ctx, tokens.gridGapDp * 2))
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(0, UiStyleProvider.dp(ctx, 8), 0, UiStyleProvider.dp(ctx, 8))
        // exactly one row of tiles: without a fixed height and row count Leanback wraps the tiles
        // into as many rows as fit in the (unbounded) parent
        grid.setNumRows(1)
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileSizePx + UiStyleProvider.dp(ctx, 16)))
        return PhotoRowViewHolder(column, title, grid)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val vh = viewHolder as PhotoRowViewHolder
        val row = item as? PhotoRow ?: return
        val ctx = vh.view.context
        vh.title.text = row.title

        val tiles = PhotoTilePresenter(ctx, tokens)
        tiles.tileSizePx = tileSizePx
        val adapter = ArrayObjectAdapter(tiles)
        adapter.addAll(0, row.assets.map { it.toCard() })
        val bridge = ItemBridgeAdapter(adapter)
        bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    (viewHolder.item as? Card)?.let { card -> onClicked(card) }
                }
            }
        })
        vh.grid.adapter = bridge
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder as PhotoRowViewHolder).grid.adapter = null
    }
}
