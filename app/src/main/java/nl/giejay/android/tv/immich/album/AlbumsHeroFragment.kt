package nl.giejay.android.tv.immich.album

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
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
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Albums page: the cover of the focused album fills the screen below the title (dark gradients
 * left / bottom / top for legibility), with name, date and count over it, and a row of album
 * cards at the very bottom.
 */
class AlbumsHeroFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    private lateinit var tokens: UiTokens
    private lateinit var heroImage: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroMeta: TextView
    private lateinit var cardsGrid: HorizontalGridView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHero: Runnable? = null
    private var shownHeroId: String? = null

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<AlbumsHeroFragment> {
        return mMainFragmentAdapter
    }

    private var appleView: AppleAlbumsView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tokens = UiStyleProvider.current()
        val ctx = requireContext()
        if (tokens.style == UiStyle.APPLE) {
            // Apple: latest album as a fixed hero, "My Albums" / "Shared Albums" rows (apple-05)
            return AppleAlbumsView(ctx, tokens) { album ->
                findNavController().navigate(
                    HomeFragmentDirections.actionHomeFragmentToAlbumDetailsFragment(album.id, album.albumName)
                )
            }.also { appleView = it }
        }
        val root = FrameLayout(ctx)
        root.setBackgroundColor(tokens.bg)
        val topOffset = UiStyleProvider.dp(ctx, 65)

        heroImage = ImageView(ctx)
        heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(heroImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = topOffset
        })

        // legibility gradients: left, bottom and top fade into the background
        fun overlay(orientation: GradientDrawable.Orientation, vararg colors: Int): View {
            val v = View(ctx)
            v.background = GradientDrawable(orientation, colors)
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = topOffset
            })
            return v
        }
        fun alpha(a: Float) = (tokens.bg and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        overlay(GradientDrawable.Orientation.LEFT_RIGHT, alpha(0.92f), alpha(0.45f), alpha(0.1f))
        overlay(GradientDrawable.Orientation.BOTTOM_TOP, alpha(1f), alpha(0.75f), Color.TRANSPARENT)
        overlay(GradientDrawable.Orientation.TOP_BOTTOM, alpha(1f), Color.TRANSPARENT)

        val padH = UiStyleProvider.dp(ctx, tokens.contentPaddingHDp)

        val title = TextView(ctx)
        title.text = "Albums"
        UiStyleProvider.applyTitle(title, tokens)
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = padH
            topMargin = UiStyleProvider.dp(ctx, tokens.contentPaddingTopDp)
        })

        val info = LinearLayout(ctx)
        info.orientation = LinearLayout.VERTICAL
        heroName = TextView(ctx)
        UiStyleProvider.applyTitle(heroName, tokens, 30f)
        heroName.maxLines = 2
        info.addView(heroName)
        heroMeta = TextView(ctx)
        UiStyleProvider.applyBody(heroMeta, tokens, 14f, tokens.dim)
        info.addView(heroMeta)
        root.addView(info, FrameLayout.LayoutParams(UiStyleProvider.dp(ctx, 380), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = padH
            topMargin = UiStyleProvider.dp(ctx, 125)
        })

        cardsGrid = HorizontalGridView(ctx)
        cardsGrid.setItemSpacing(UiStyleProvider.dp(ctx, 12))
        cardsGrid.clipChildren = false
        cardsGrid.clipToPadding = false
        cardsGrid.setPadding(padH, UiStyleProvider.dp(ctx, 10), padH, UiStyleProvider.dp(ctx, 10))
        root.addView(cardsGrid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiStyleProvider.dp(ctx, 150)).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = UiStyleProvider.dp(ctx, 16)
        })
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

    /** Apple: own albums show up as soon as they arrive, the (slower) shared list follows. */
    private fun loadAppleAlbums(client: nl.giejay.android.tv.immich.api.ApiClient) {
        viewLifecycleOwner.lifecycleScope.launch {
            val mine = withContext(Dispatchers.IO) { client.listMyAlbums() }
            val view = appleView ?: return@launch
            mine.fold({ Timber.e("Could not load albums: $it") }, { albums ->
                view.setMyAlbums(sortAlbums(albums))
                // the most recent album (date of its newest photo), whatever the sorting of the rows;
                // empty albums have no cover, so they cannot be the hero
                albums.filter { it.assetCount > 0 && it.albumThumbnailAssetId != null }
                    .maxByOrNull { (it.endDate ?: it.updatedAt).time }
                    ?.let { view.showHero(it) }
            })
            val shared = withContext(Dispatchers.IO) { client.listSharedAlbums() }
            val mineIds = (mine.getOrNull() ?: emptyList()).map { it.id }.toSet()
            shared.fold({ Timber.e("Could not load shared albums: $it") }, { albums ->
                appleView?.setSharedAlbums(sortAlbums(albums.filter { it.id !in mineIds }))
            })
        }
    }

    private fun loadAlbums() {
        val client = currentApiClient() ?: return
        if (appleView != null) {
            loadAppleAlbums(client)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.listAlbums() }
            result.fold(
                { Timber.e("Could not load albums: $it") },
                { albums ->
                    val sorted = try {
                        albums.sortedWith(PreferenceManager.get(ALBUMS_SORTING).sort)
                    } catch (e: IllegalArgumentException) {
                        albums
                    }
                    showAlbums(sorted)
                }
            )
        }
    }

    private fun showAlbums(albums: List<Album>) {
        if (!isAdded || view == null || albums.isEmpty()) return
        val presenter = AlbumCardPresenter(tokens, { album -> scheduleHero(album) }, { album ->
            findNavController().navigate(
                HomeFragmentDirections.actionHomeFragmentToAlbumDetailsFragment(album.id, album.albumName)
            )
        })
        val adapter = ArrayObjectAdapter(presenter)
        adapter.addAll(0, albums)
        cardsGrid.adapter = ItemBridgeAdapter(adapter)
        showHero(albums.first())
    }

    private fun scheduleHero(album: Album) {
        pendingHero?.let { handler.removeCallbacks(it) }
        val r = Runnable { showHero(album) }
        pendingHero = r
        handler.postDelayed(r, 150)
    }

    private fun showHero(album: Album) {
        if (!isAdded || shownHeroId == album.id) return
        shownHeroId = album.id
        heroName.text = album.albumName
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val date = (album.endDate ?: album.updatedAt)?.let {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale).format(it)
        }
        val count = "${album.assetCount} photos"
        heroMeta.text = listOfNotNull(date, count).joinToString("  ·  ")
        val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "preview")
        if (url != null) {
            Glide.with(this).load(url).centerCrop().into(heroImage)
        } else {
            heroImage.setImageDrawable(null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingHero?.let { handler.removeCallbacks(it) }
        shownHeroId = null
        appleView = null
    }
}

/** Album card of the bottom row: cover, name and count. */
private class AlbumCardPresenter(
    private val tokens: UiTokens,
    private val onFocused: (Album) -> Unit,
    private val onClicked: (Album) -> Unit
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = FocusCardView(ctx, tokens)
        val w = UiStyleProvider.dp(ctx, 120)
        card.layoutParams = ViewGroup.MarginLayoutParams(w, UiStyleProvider.dp(ctx, 120))
        card.setBackgroundColor(tokens.pillBg)

        val image = ImageView(ctx)
        image.id = View.generateViewId()
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(w, UiStyleProvider.dp(ctx, 76)))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(UiStyleProvider.dp(ctx, 8), UiStyleProvider.dp(ctx, 4), UiStyleProvider.dp(ctx, 8), 0)
        val name = TextView(ctx)
        UiStyleProvider.applyBody(name, tokens, 13f, tokens.text)
        name.setSingleLine(true)
        val count = TextView(ctx)
        UiStyleProvider.applyBody(count, tokens, 13f, tokens.dim)
        count.setSingleLine(true)
        texts.addView(name)
        texts.addView(count)
        card.addView(texts, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiStyleProvider.dp(ctx, 76)
        })
        card.tag = arrayOf<Any>(image, name, count)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val album = item as? Album ?: return
        val card = viewHolder.view as FocusCardView
        @Suppress("UNCHECKED_CAST")
        val parts = card.tag as Array<Any>
        val image = parts[0] as ImageView
        (parts[1] as TextView).text = album.albumName
        (parts[2] as TextView).text = "${album.assetCount}"
        val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "thumbnail")
        if (url != null) Glide.with(image).asBitmap().load(url).centerCrop().into(image) else image.setImageDrawable(null)
        card.onFocusGained = { onFocused(album) }
        card.setOnClickListener { onClicked(album) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as FocusCardView
        card.resetFocusState()
        card.onFocusGained = null
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
