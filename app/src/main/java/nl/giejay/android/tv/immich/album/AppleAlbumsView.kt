package nl.giejay.android.tv.immich.album

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.ui.FocusCardView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Albums page of the Apple style (mockup apple-05): the most recent album full bleed at the top,
 * then "My Albums" and "Shared Albums" rows of 420x280px cards. Everything is laid out at fixed
 * positions of the 1080p artboard (1px = 0.5dp) inside [content], which slides up when the focus
 * goes to the shared row.
 */
class AppleAlbumsView(
    context: Context,
    private val tokens: UiTokens,
    private val onAlbumClick: (Album) -> Unit
) : FrameLayout(context) {

    private val content = FrameLayout(context)
    private val heroImage = ImageView(context)
    private val heroName = TextView(context)
    private val heroMeta = TextView(context)
    private val heroText = LinearLayout(context)
    private val myBlock = LinearLayout(context)
    private val sharedBlock = LinearLayout(context)
    private val myAdapter = ArrayObjectAdapter(cardPresenter { 0 })
    private val sharedAdapter = ArrayObjectAdapter(cardPresenter { 1 })
    private var shownHeroId: String? = null

    private fun dp(v: Float) = UiStyleProvider.dp(context, v)
    private fun dp(v: Int) = UiStyleProvider.dp(context, v)

    init {
        setBackgroundColor(tokens.bg)
        clipChildren = false
        content.clipChildren = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, dp(PAGE_HEIGHT_DP)))

        // hero: 1920x560px, image + gradient (rgba(0,0,0,.3) -> transparent at 35% -> bg)
        heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
        content.addView(heroImage, LayoutParams(LayoutParams.MATCH_PARENT, dp(HERO_HEIGHT_DP)))
        val shade = View(context)
        shade.background = PaintDrawable().apply {
            shape = RectShape()
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x4D000000, Color.TRANSPARENT, tokens.bg),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
                )
            }
        }
        content.addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, dp(HERO_HEIGHT_DP)))

        // text block at left 96px, top 290px
        heroText.orientation = LinearLayout.VERTICAL
        val label = TextView(context)
        label.text = "LATEST ALBUM"
        label.includeFontPadding = false
        UiStyleProvider.applyBody(label, tokens, 11f, 0xD9FFFFFF.toInt())
        label.typeface = UiStyleProvider.titleTypeface(tokens)
        label.letterSpacing = 0.08f
        heroText.addView(label)
        heroName.includeFontPadding = false
        UiStyleProvider.applyTitle(heroName, tokens, 42f)
        heroName.setTextColor(Color.WHITE)
        heroName.letterSpacing = -0.03f
        heroName.maxLines = 1
        heroName.ellipsize = android.text.TextUtils.TruncateAt.END
        heroText.addView(heroName, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        heroMeta.includeFontPadding = false
        UiStyleProvider.applyBody(heroMeta, tokens, 13f, 0xD9FFFFFF.toInt())
        heroText.addView(heroMeta, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        listOf(label, heroName, heroMeta).forEach { it.setShadowLayer(dp(12).toFloat(), 0f, dp(1).toFloat(), 0x73000000) }
        content.addView(heroText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(PAD_LEFT_DP)
            topMargin = dp(145)
        })

        buildSection(myBlock, "My Albums", myAdapter, MY_TOP_DP)
        buildSection(sharedBlock, "Shared Albums", sharedAdapter, SHARED_TOP_DP)
        sharedBlock.visibility = View.GONE
    }

    private fun buildSection(block: LinearLayout, title: String, adapter: ArrayObjectAdapter, topDp: Int) {
        block.orientation = LinearLayout.VERTICAL
        block.clipChildren = false
        val heading = TextView(context)
        heading.text = title
        heading.includeFontPadding = false
        UiStyleProvider.applyTitle(heading, tokens, 18f)
        block.addView(heading, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(PAD_LEFT_DP)
            bottomMargin = dp(3)
        })
        val grid = HorizontalGridView(context)
        grid.setItemSpacing(dp(16))
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.setPadding(dp(PAD_LEFT_DP), dp(8), dp(PAD_LEFT_DP), 0)
        grid.adapter = ItemBridgeAdapter(adapter)
        block.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CARD_ROW_HEIGHT_DP)))
        content.addView(block, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp)
        })
    }

    /** Fills the hero with [album] (the most recent one). */
    fun showHero(album: Album) {
        if (shownHeroId == album.id) return
        shownHeroId = album.id
        heroName.text = album.albumName
        heroMeta.text = listOfNotNull(albumDate(album), photoCount(album.assetCount)).joinToString(" · ")
        val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "preview")
        if (url != null) Glide.with(this).load(url).centerCrop().into(heroImage) else heroImage.setImageDrawable(null)
    }

    fun setMyAlbums(albums: List<Album>) {
        myAdapter.clear()
        myAdapter.addAll(0, albums)
        myBlock.visibility = if (albums.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setSharedAlbums(albums: List<Album>) {
        sharedAdapter.clear()
        sharedAdapter.addAll(0, albums)
        sharedBlock.visibility = if (albums.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Slides the page so the focused row is in view. */
    private fun rowFocused(row: Int) {
        val target = if (row == 0) 0f else -dp(SHARED_TOP_DP - SHARED_SCROLLED_TOP_DP).toFloat()
        if (content.translationY != target) content.animate().translationY(target).setDuration(200).start()
    }

    private fun albumDate(album: Album): String? {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        return (album.endDate ?: album.updatedAt)?.let {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale).format(it)
        }
    }

    private fun photoCount(count: Int): String =
        "${NumberFormat.getIntegerInstance().format(count)} ${if (count == 1) "photo" else "photos"}"

    private fun cardPresenter(row: () -> Int) = object : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(AlbumCard(parent.context))

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val album = item as? Album ?: return
            val card = viewHolder.view as AlbumCard
            card.bind(album)
            card.cover.onFocusGained = { rowFocused(row()) }
            card.cover.setOnClickListener { onAlbumClick(album) }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            (viewHolder.view as AlbumCard).unbind()
        }
    }

    /** Cover (420x280px, 18px corners) with name and photo count below it. */
    private inner class AlbumCard(context: Context) : LinearLayout(context) {
        val cover = FocusCardView(context, tokens, focusScale = 1.07f, radiusDp = 9f)
        private val image = ImageView(context)
        private val name = TextView(context)
        private val count = TextView(context)

        init {
            orientation = VERTICAL
            clipChildren = false
            layoutParams = ViewGroup.MarginLayoutParams(dp(210), LayoutParams.WRAP_CONTENT)
            cover.setBackgroundColor(tokens.pillBg)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(cover, LayoutParams(dp(210), dp(140)))

            name.includeFontPadding = false
            name.setSingleLine(true)
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            UiStyleProvider.applyTitle(name, tokens, 12f)
            name.letterSpacing = 0f
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            count.includeFontPadding = false
            count.setSingleLine(true)
            UiStyleProvider.applyBody(count, tokens, 10f, tokens.dim)
            addView(count, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })

            // the focused cover grows by ~5dp: the texts follow it (mockup: 24px margin instead of 14px)
            cover.setOnFocusChangeListener { _, focused ->
                val shift = if (focused) dp(5).toFloat() else 0f
                name.animate().translationY(shift).setDuration(120).start()
                count.animate().translationY(shift).setDuration(120).start()
                name.alpha = if (focused) 1f else 0.9f
            }
        }

        fun bind(album: Album) {
            name.text = album.albumName
            count.text = photoCount(album.assetCount)
            val url = ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "preview")
            if (url != null) Glide.with(image).asBitmap().load(url).centerCrop().into(image) else image.setImageDrawable(null)
        }

        fun unbind() {
            cover.resetFocusState()
            cover.onFocusGained = null
            cover.setOnClickListener(null)
            name.translationY = 0f
            count.translationY = 0f
            try {
                Glide.with(image).clear(image)
            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            }
        }
    }

    companion object {
        // positions of the 1920x1080 artboard divided by 2
        private const val PAD_LEFT_DP = 48
        private const val HERO_HEIGHT_DP = 280
        private const val MY_TOP_DP = 285
        private const val SHARED_TOP_DP = 500
        /** where the "Shared Albums" heading sits once the page has slid up */
        private const val SHARED_SCROLLED_TOP_DP = 30
        private const val CARD_ROW_HEIGHT_DP = 190
        private const val PAGE_HEIGHT_DP = 760
    }
}
