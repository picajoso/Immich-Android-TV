package nl.giejay.android.tv.immich.album

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.TextUtils
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.ALBUMS_SORTING
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.ui.FocusCardView
import nl.giejay.android.tv.immich.ui.UiFormat
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Albums page. Apple: the latest album as a fixed hero with "My Albums" / "Shared Albums" rows
 * ([AppleAlbumsView]). Google TV: the cover of the focused album fills the screen below the title
 * (gradients fade it into the background), with name, date and count over it, and one row of album
 * cards at the very bottom.
 */
class AlbumsHeroFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    private lateinit var tokens: UiTokens
    private var heroImage: ImageView? = null
    private var heroLabel: TextView? = null
    private var heroName: TextView? = null
    private var heroMeta: TextView? = null
    private var cardsGrid: HorizontalGridView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHero: Runnable? = null
    private var shownHeroId: String? = null
    private var latestAlbumId: String? = null
    private var appleView: AppleAlbumsView? = null

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<AlbumsHeroFragment> {
        return mMainFragmentAdapter
    }

    private fun openAlbum(album: Album) {
        findNavController().navigate(
            HomeFragmentDirections.actionHomeFragmentToAlbumDetailsFragment(album.id, album.albumName)
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        if (tokens.style == UiStyle.APPLE) {
            // Apple: latest album as a fixed hero, "My Albums" / "Shared Albums" rows (apple-05)
            return AppleAlbumsView(ctx, tokens) { openAlbum(it) }.also { appleView = it }
        }
        return buildGoogleView()
    }

    // ----- Google TV (googletv-05) -----

    private fun buildGoogleView(): View {
        val ctx = requireContext()
        fun dp(v: Int) = UiStyleProvider.dp(ctx, v)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(tokens.bg)
        root.clipChildren = false

        // cover from y=130px to the bottom, faded into the background by three gradients
        val heroTop = dp(65)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = heroTop
        })
        heroImage = image

        fun bgAlpha(a: Float) = (tokens.bg and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        fun fade(horizontal: Boolean, colors: IntArray, stops: FloatArray) {
            val v = View(ctx)
            v.background = PaintDrawable().apply {
                shape = RectShape()
                shaderFactory = object : ShapeDrawable.ShaderFactory() {
                    override fun resize(width: Int, height: Int): Shader =
                        if (horizontal) LinearGradient(0f, 0f, width.toFloat(), 0f, colors, stops, Shader.TileMode.CLAMP)
                        else LinearGradient(0f, 0f, 0f, height.toFloat(), colors, stops, Shader.TileMode.CLAMP)
                }
            }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = heroTop
            })
        }
        // 90deg: rgba(bg,.92) -> .45 at 50% -> .1
        fade(true, intArrayOf(bgAlpha(0.92f), bgAlpha(0.45f), bgAlpha(0.1f)), floatArrayOf(0f, 0.5f, 1f))
        // 0deg (bottom up): bg -> .75 at 30% -> transparent at 60%; expressed top down
        fade(false, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, bgAlpha(0.75f), tokens.bg), floatArrayOf(0f, 0.4f, 0.7f, 1f))
        // 180deg: bg at the top -> transparent at 14%
        fade(false, intArrayOf(tokens.bg, Color.TRANSPARENT, Color.TRANSPARENT), floatArrayOf(0f, 0.14f, 1f))

        val padH = dp(tokens.contentPaddingHDp)
        val title = TextView(ctx)
        title.text = "Albums"
        title.includeFontPadding = false
        UiStyleProvider.applyTitle(title, tokens)
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = padH
            topMargin = dp(tokens.contentPaddingTopDp)
        })

        // left 80px, top 250px: label (accent), name (80px), "Aug 2026 · 214 photos"
        val info = LinearLayout(ctx)
        info.orientation = LinearLayout.VERTICAL
        val label = TextView(ctx)
        label.includeFontPadding = false
        UiStyleProvider.applyBody(label, tokens, 13f, tokens.accent)
        label.typeface = UiStyleProvider.titleTypeface(tokens)
        info.addView(label)
        val name = TextView(ctx)
        name.includeFontPadding = false
        UiStyleProvider.applyTitle(name, tokens, 40f)
        name.letterSpacing = -0.02f
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        info.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        val meta = TextView(ctx)
        meta.includeFontPadding = false
        UiStyleProvider.applyBody(meta, tokens, 14f, tokens.dim)
        info.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        root.addView(info, FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = padH
            topMargin = dp(125)
        })
        heroLabel = label
        heroName = name
        heroMeta = meta

        // row of cards at the very bottom: left 80px, bottom 48px, 28px between cards
        val grid = HorizontalGridView(ctx)
        grid.setItemSpacing(dp(14))
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(padH, dp(10), padH, 0)
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            gravity = Gravity.BOTTOM
            // the texts end 48px above the bottom edge in the mockup
            bottomMargin = -dp(4)
        })
        cardsGrid = grid
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMainFragmentAdapter.fragmentHost?.showTitleView(false)
        loadAlbums()
    }

    private fun sortAlbums(albums: List<Album>): List<Album> = try {
        albums.sortedWith(PreferenceManager.get(ALBUMS_SORTING).sort)
    } catch (e: IllegalArgumentException) {
        albums
    }

    /** The most recent album (date of its newest photo); empty albums have no cover. */
    private fun latestOf(albums: List<Album>): Album? =
        albums.filter { it.assetCount > 0 && it.albumThumbnailAssetId != null }
            .maxByOrNull { (it.endDate ?: it.updatedAt).time }

    private fun loadAlbums() {
        val client = currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val mine = withContext(Dispatchers.IO) { client.listMyAlbums() }
            val mineList = mine.getOrNull()
            if (mineList == null) {
                Timber.e("Could not load albums")
                return@launch
            }
            val apple = appleView
            if (apple != null) {
                // Apple: own albums show up as soon as they arrive, the (slower) shared list follows
                apple.setMyAlbums(sortAlbums(mineList))
                latestOf(mineList)?.let { apple.showHero(it) }
                val shared = withContext(Dispatchers.IO) { client.listSharedAlbums() }
                val mineIds = mineList.map { it.id }.toSet()
                shared.getOrNull()?.let { list -> appleView?.setSharedAlbums(sortAlbums(list.filter { it.id !in mineIds })) }
            } else {
                // Google TV: one row, own albums first, then the shared ones not already in it
                showGoogleAlbums(sortAlbums(mineList))
                val shared = withContext(Dispatchers.IO) { client.listSharedAlbums() }
                val mineIds = mineList.map { it.id }.toSet()
                val extra = shared.getOrNull()?.filter { it.id !in mineIds }.orEmpty()
                if (extra.isNotEmpty() && isAdded) showGoogleAlbums(sortAlbums(mineList + extra))
            }
        }
    }

    private fun showGoogleAlbums(albums: List<Album>) {
        val grid = cardsGrid ?: return
        if (!isAdded || view == null || albums.isEmpty()) return
        latestAlbumId = latestOf(albums)?.id
        val presenter = GoogleAlbumCardPresenter(tokens, { album -> scheduleHero(album) }, { album -> openAlbum(album) })
        val adapter = ArrayObjectAdapter(presenter)
        adapter.addAll(0, albums)
        grid.adapter = ItemBridgeAdapter(adapter)
        showHero(albums.first())
    }

    private fun scheduleHero(album: Album) {
        pendingHero?.let { handler.removeCallbacks(it) }
        val r = Runnable { showHero(album) }
        pendingHero = r
        handler.postDelayed(r, 150)
    }

    private fun showHero(album: Album) {
        val image = heroImage ?: return
        if (!isAdded || shownHeroId == album.id) return
        shownHeroId = album.id
        // the label only makes sense for the latest album
        heroLabel?.text = if (album.id == latestAlbumId) "Latest album" else ""
        heroName?.text = album.albumName
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val date = (album.endDate ?: album.updatedAt)?.let {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale).format(it)
        }
        heroMeta?.text = listOfNotNull(date, "${UiFormat.count(album.assetCount)} ${if (album.assetCount == 1) "photo" else "photos"}").joinToString(" · ")
        val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "preview")
        if (url != null) Glide.with(this).load(url).centerCrop().into(image) else image.setImageDrawable(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingHero?.let { handler.removeCallbacks(it) }
        shownHeroId = null
        appleView = null
        heroImage = null
        heroLabel = null
        heroName = null
        heroMeta = null
        cardsGrid = null
    }
}

/** Album card of the Google TV row: 340x220px cover (14px corners), name and count below it. */
private class GoogleAlbumCardPresenter(
    private val tokens: UiTokens,
    private val onFocused: (Album) -> Unit,
    private val onClicked: (Album) -> Unit
) : Presenter() {

    private class Parts(val cover: FocusCardView, val image: ImageView, val name: TextView, val count: TextView)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        fun dp(v: Int) = UiStyleProvider.dp(ctx, v)
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.clipChildren = false
        card.layoutParams = ViewGroup.MarginLayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT)

        val cover = FocusCardView(ctx, tokens, radiusDp = 7f)
        cover.setBackgroundColor(tokens.pillBg)
        val image = ImageView(ctx)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(cover, LinearLayout.LayoutParams(dp(170), dp(110)))

        val name = TextView(ctx)
        name.includeFontPadding = false
        name.setSingleLine(true)
        name.ellipsize = TextUtils.TruncateAt.END
        UiStyleProvider.applyBody(name, tokens, 13f, tokens.text)
        card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        val count = TextView(ctx)
        count.includeFontPadding = false
        count.setSingleLine(true)
        UiStyleProvider.applyBody(count, tokens, 12f, tokens.dim)
        card.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })

        // the focused cover grows ~4dp: the texts follow it and the name turns bold
        cover.setOnFocusChangeListener { _, focused ->
            val shift = if (focused) dp(4).toFloat() else 0f
            name.animate().translationY(shift).setDuration(120).start()
            count.animate().translationY(shift).setDuration(120).start()
            name.typeface = if (focused) UiStyleProvider.titleTypeface(tokens) else UiStyleProvider.bodyTypeface(tokens)
        }
        card.tag = Parts(cover, image, name, count)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val album = item as? Album ?: return
        val p = viewHolder.view.tag as Parts
        p.name.text = album.albumName
        p.count.text = "${UiFormat.count(album.assetCount)} ${if (album.assetCount == 1) "photo" else "photos"}"
        val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "preview")
        if (url != null) Glide.with(p.image).asBitmap().load(url).centerCrop().into(p.image) else p.image.setImageDrawable(null)
        p.cover.onFocusGained = { onFocused(album) }
        p.cover.setOnClickListener { onClicked(album) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val p = viewHolder.view.tag as Parts
        p.cover.resetFocusState()
        p.cover.onFocusGained = null
        p.cover.setOnClickListener(null)
        p.name.translationY = 0f
        p.count.translationY = 0f
        try {
            Glide.with(p.image).clear(p.image)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
    }
}
