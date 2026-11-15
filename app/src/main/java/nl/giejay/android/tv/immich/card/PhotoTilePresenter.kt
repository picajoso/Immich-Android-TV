package nl.giejay.android.tv.immich.card

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import timber.log.Timber

/**
 * Square photo tile of the continuous photo grid. Look (radius, focus scale, ring) comes from
 * [UiTokens]. The edge length is set by the grid (see PhotoGridLayout) through [tileSizePx].
 */
class PhotoTilePresenter(private val context: Context, private val tokens: UiTokens) : Presenter() {

    var onLongClick: ((ICard) -> Unit)? = null

    /** Edge of the square tile in px, 0 until the grid has measured itself. */
    var tileSizePx: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        return ViewHolder(PhotoTileView(parent.context, tokens))
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tile = viewHolder.view as PhotoTileView
        val card = item as? ICard ?: return

        val size = if (tileSizePx > 0) tileSizePx else UiStyleProvider.dp(context, tokens.gridCellDp)
        val lp = tile.layoutParams
        if (lp == null) {
            tile.layoutParams = ViewGroup.MarginLayoutParams(size, size)
        } else if (lp.width != size || lp.height != size) {
            lp.width = size
            lp.height = size
            tile.layoutParams = lp
        }

        tile.setOnLongClickListener {
            try {
                onLongClick?.invoke(card)
            } catch (e: Exception) {
                Timber.e(e, "Error on long click for card: ${card.id}")
            }
            true
        }

        tile.syncFocusState()

        val realCard = card as? Card
        tile.setBadges(isVideo = realCard?.isVideo == true, isFavorite = realCard?.isFavorite == true)

        val url = card.thumbnailUrl
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            Glide.with(tile.image).asBitmap().load(url).centerCrop().into(tile.image)
        } else {
            tile.image.setImageDrawable(null)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val tile = viewHolder.view as PhotoTileView
        tile.resetFocusState()
        tile.setOnLongClickListener(null)
        try {
            Glide.with(tile.image).clear(tile.image)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }
        tile.image.setImageDrawable(null)
    }
}

/**
 * Image + optional heart / play badges + focus ring and scale. Focus feedback is done in
 * onFocusChanged because Leanback replaces any OnFocusChangeListener set on grid items.
 */
class PhotoTileView(context: Context, private val tokens: UiTokens) : FrameLayout(context) {
    private val radius = UiStyleProvider.dp(context, tokens.tileRadiusDp).toFloat()
    private val focusedRadius = UiStyleProvider.dp(context, tokens.focusedTileRadiusDp).toFloat()
    private var cornerRadius = radius
    val image: ImageView = ImageView(context)
    private val heart = ImageView(context)
    private val play = ImageView(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        clipChildren = true

        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val badge = UiStyleProvider.dp(context, 14)
        val margin = UiStyleProvider.dp(context, 4)
        heart.setImageResource(R.drawable.ic_favorite_white_filled_24dp)
        heart.visibility = GONE
        addView(heart, LayoutParams(badge, badge, Gravity.TOP or Gravity.END).apply {
            setMargins(margin, margin, margin, margin)
        })
        play.setImageResource(android.R.drawable.ic_media_play)
        play.setColorFilter(Color.WHITE)
        play.visibility = GONE
        addView(play, LayoutParams(badge, badge, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(margin, margin, margin, margin)
        })

        // placeholder color while the thumbnail loads; also gives the shadow a surface
        background = ColorDrawable(tokens.pillBg)
        foreground = UiStyleProvider.tileFocusRing(context, tokens)

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    fun setBadges(isVideo: Boolean, isFavorite: Boolean) {
        play.visibility = if (isVideo) VISIBLE else GONE
        heart.visibility = if (isFavorite) VISIBLE else GONE
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) tokens.focusScale else 1f
        cornerRadius = if (gainFocus) focusedRadius else radius
        invalidateOutline()
        val z = if (gainFocus) UiStyleProvider.dp(context, 12).toFloat() else 0f
        animate().scaleX(scale).scaleY(scale).translationZ(z).setDuration(120).start()
    }

    /** Applies the focus look immediately (no animation), e.g. after a rebind. */
    fun syncFocusState() {
        animate().cancel()
        val focused = isFocused
        scaleX = if (focused) tokens.focusScale else 1f
        scaleY = scaleX
        translationZ = if (focused) UiStyleProvider.dp(context, 12).toFloat() else 0f
        cornerRadius = if (focused) focusedRadius else radius
        invalidateOutline()
    }

    fun resetFocusState() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        translationZ = 0f
        cornerRadius = radius
        invalidateOutline()
    }
}
