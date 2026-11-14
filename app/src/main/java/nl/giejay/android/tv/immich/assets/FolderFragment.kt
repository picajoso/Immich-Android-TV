package nl.giejay.android.tv.immich.assets

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
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Folder
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.home.HomeFragment
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.shared.util.openSliderFor
import nl.giejay.android.tv.immich.ui.FocusCardView
import nl.giejay.android.tv.immich.ui.PageKit
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber

/** Identity wrapper: Folder is a data class whose equals walks parent and children. */
class FolderEntry(val folder: Folder)

/**
 * Folders: folders only (photos of the current folder follow them), six per row, each folder
 * cover a 3-photo split mosaic, breadcrumb in the header and a Timeline / Folders toggle.
 */
class FolderFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter<Fragment>(this)

    private lateinit var tokens: UiTokens
    private lateinit var breadcrumb: TextView
    private lateinit var upButton: TextView
    private lateinit var grid: VerticalGridView
    private var client: ApiClient? = null
    private var root: Folder? = null
    private var current: Folder? = null
    private var presenter: FolderCardPresenter? = null
    private var adapter: ArrayObjectAdapter? = null
    private var shownAssets: List<Asset> = emptyList()
    private val coverCache = mutableMapOf<String, List<Asset>>()
    private var lastWidth = -1

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> = mMainFragmentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        val rootView = LinearLayout(ctx)
        rootView.orientation = LinearLayout.VERTICAL
        rootView.setBackgroundColor(tokens.bg)
        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(padH, UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp), padH, UiStyleProvider.dp(ctx, 10))

        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(ctx)
        title.text = "Folders"
        UiStyleProvider.applyTitle(title, tokens)
        titles.addView(title)
        breadcrumb = TextView(ctx)
        UiStyleProvider.applyBody(breadcrumb, tokens, 14f, tokens.dim)
        breadcrumb.maxLines = 1
        titles.addView(breadcrumb)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        upButton = PageKit.pill(ctx, tokens, "‹ Up", selected = false, focusable = true) { goUp() }
        upButton.visibility = View.GONE
        header.addView(upButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UiStyleProvider.dp(ctx, 16)
        })
        val timeline = PageKit.pill(ctx, tokens, "Timeline", selected = false, focusable = true) {
            (parentFragment as? HomeFragment)?.selectPage("Timeline")
        }
        header.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UiStyleProvider.dp(ctx, 8)
        })
        val folders = PageKit.pill(ctx, tokens, "Folders", selected = true, focusable = true)
        header.addView(folders)
        rootView.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid = VerticalGridView(ctx)
        grid.setNumColumns(COLUMNS)
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(padH, UiStyleProvider.dp(ctx, 12), padH, UiStyleProvider.dp(ctx, 24))
        grid.setHorizontalSpacing(UiStyleProvider.dp(ctx, GAP_DP))
        grid.setVerticalSpacing(UiStyleProvider.dp(ctx, GAP_DP))
        rootView.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        grid.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width != lastWidth) {
                lastWidth = width
                v.post { sizeCards() }
            }
        }
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
        val c = currentApiClient() ?: return
        client = c
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { c.listFolders() }
            result.fold(
                { Timber.e("Could not load folders: $it") },
                { folder ->
                    root = folder
                    enter(folder)
                }
            )
        }
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

    private fun goUp() {
        current?.parent?.let { enter(it) }
    }

    private fun enter(folder: Folder) {
        if (!isAdded || view == null) return
        current = folder
        updateBreadcrumb(folder)
        upButton.visibility = if (folder.parent != null) View.VISIBLE else View.GONE

        val p = presenter ?: FolderCardPresenter(tokens, { entry, cb -> loadCovers(entry.folder, cb) }) { entry -> enter(entry.folder) }
            .also { presenter = it }
        p.onAssetClicked = { asset -> openSliderFor(shownAssets, asset.id) }
        val a = ArrayObjectAdapter(p)
        adapter = a
        shownAssets = emptyList()
        a.addAll(0, folder.children.sortedBy { it.path.lowercase() }.map { FolderEntry(it) })
        grid.adapter = ItemBridgeAdapter(a)
        grid.post { sizeCards() }

        // photos of this folder follow its sub folders
        val c = client ?: return
        val path = folder.getFullPath()
        viewLifecycleOwner.lifecycleScope.launch {
            val assets = withContext(Dispatchers.IO) { c.listAssetsForFolder(path).getOrNull().orEmpty() }
            coverCache[path] = assets
            if (!isAdded || view == null || current !== folder) return@launch
            shownAssets = assets
            a.addAll(a.size(), assets)
        }
    }

    private fun updateBreadcrumb(folder: Folder) {
        val names = mutableListOf<String>()
        var f: Folder? = folder
        while (f != null) {
            names.add(0, if (f.parent == null) "Library" else f.path)
            f = f.parent
        }
        breadcrumb.text = names.filter { it.isNotEmpty() }.joinToString("  ›  ")
    }

    private fun loadCovers(folder: Folder, callback: (List<Asset>) -> Unit) {
        val path = folder.getFullPath()
        coverCache[path]?.let {
            callback(it)
            return
        }
        val c = client ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val assets = withContext(Dispatchers.IO) { c.listAssetsForFolder(path).getOrNull().orEmpty() }
            coverCache[path] = assets
            if (isAdded && view != null) callback(assets)
        }
    }

    companion object {
        private const val COLUMNS = 6
        private const val GAP_DP = 12
    }
}

private class FolderCardViewHolder(
    view: View,
    val big: ImageView,
    val topRight: ImageView,
    val bottomRight: ImageView,
    val name: TextView,
    val count: TextView
) : Presenter.ViewHolder(view) {
    var boundKey: String? = null
}

private class FolderCardPresenter(
    private val tokens: UiTokens,
    private val coversFor: (FolderEntry, (List<Asset>) -> Unit) -> Unit,
    private val onFolderClicked: (FolderEntry) -> Unit
) : Presenter() {
    var cellW = 0
    var cellH = 0
    var onAssetClicked: (Asset) -> Unit = {}

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = FocusCardView(ctx, tokens)
        card.setBackgroundColor(tokens.pillBg)

        // split mosaic: big photo on the left, two stacked on the right
        val mosaic = LinearLayout(ctx)
        mosaic.orientation = LinearLayout.HORIZONTAL
        val gap = UiStyleProvider.dp(ctx, 1)
        fun image(): ImageView = ImageView(ctx).also { it.scaleType = ImageView.ScaleType.CENTER_CROP }
        val big = image()
        val topRight = image()
        val bottomRight = image()
        mosaic.addView(big, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        val right = LinearLayout(ctx)
        right.orientation = LinearLayout.VERTICAL
        right.addView(topRight, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = gap })
        right.addView(bottomRight, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mosaic.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap })
        card.addView(mosaic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scrim = View(ctx)
        scrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xD9000000.toInt(), 0x00000000))
        card.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiStyleProvider.dp(ctx, 60)).apply {
            gravity = Gravity.BOTTOM
        })
        val labels = LinearLayout(ctx)
        labels.orientation = LinearLayout.VERTICAL
        labels.setPadding(UiStyleProvider.dp(ctx, 10), 0, UiStyleProvider.dp(ctx, 10), UiStyleProvider.dp(ctx, 8))
        val name = TextView(ctx)
        UiStyleProvider.applyTitle(name, tokens, 15f)
        name.setTextColor(0xFFFFFFFF.toInt())
        name.maxLines = 1
        val count = TextView(ctx)
        UiStyleProvider.applyBody(count, tokens, 13f, 0xCCFFFFFF.toInt())
        labels.addView(name)
        labels.addView(count)
        card.addView(labels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        card.layoutParams = ViewGroup.MarginLayoutParams(UiStyleProvider.dp(ctx, 150), UiStyleProvider.dp(ctx, 112))
        return FolderCardViewHolder(card, big, topRight, bottomRight, name, count)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val vh = viewHolder as FolderCardViewHolder
        val card = vh.view as FocusCardView
        if (cellW > 0) {
            val lp = card.layoutParams
            if (lp.width != cellW || lp.height != cellH) {
                lp.width = cellW
                lp.height = cellH
                card.layoutParams = lp
            }
        }
        clear(vh)
        when (item) {
            is FolderEntry -> {
                vh.name.text = item.folder.path
                vh.count.text = ""
                vh.boundKey = item.folder.getFullPath()
                val key = vh.boundKey
                card.setOnClickListener { onFolderClicked(item) }
                coversFor(item) { assets ->
                    if (vh.boundKey == key) showCovers(vh, assets)
                }
            }
            is Asset -> {
                vh.boundKey = item.id
                vh.name.text = item.originalFileName ?: ""
                vh.count.text = ""
                vh.right().visibility = View.GONE
                load(vh.big, item.id)
                card.setOnClickListener { onAssetClicked(item) }
            }
        }
    }

    private fun FolderCardViewHolder.right(): View = topRight.parent as View

    private fun showCovers(vh: FolderCardViewHolder, assets: List<Asset>) {
        vh.count.text = "${assets.size} items"
        assets.getOrNull(0)?.let { load(vh.big, it.id) }
        val rightColumn = vh.right()
        if (assets.size >= 2) {
            rightColumn.visibility = View.VISIBLE
            load(vh.topRight, assets[1].id)
            assets.getOrNull(2)?.let { load(vh.bottomRight, it.id) }
        } else {
            rightColumn.visibility = View.GONE
        }
    }

    private fun load(image: ImageView, assetId: String) {
        val url = ApiUtil.getThumbnailUrl(assetId, "thumbnail") ?: return
        Glide.with(image).load(url).centerCrop().into(image)
    }

    private fun clear(vh: FolderCardViewHolder) {
        vh.right().visibility = View.VISIBLE
        listOf(vh.big, vh.topRight, vh.bottomRight).forEach { image ->
            try {
                Glide.with(image).clear(image)
            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            }
            image.setImageDrawable(null)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as FolderCardViewHolder
        (vh.view as FocusCardView).resetFocusState()
        vh.view.setOnClickListener(null)
        vh.boundKey = null
        clear(vh)
    }
}
