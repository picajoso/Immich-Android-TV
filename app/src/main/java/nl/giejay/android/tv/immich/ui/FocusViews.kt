package nl.giejay.android.tv.immich.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Card with the focus feedback of the active style (ring, scale, shadow) and rounded corners.
 * Focus feedback lives in onFocusChanged because Leanback may replace OnFocusChangeListeners.
 */
open class FocusCardView(
    context: Context,
    private val tokens: UiTokens,
    private val focusScale: Float = tokens.focusScale,
    private val radiusDp: Float? = null
) : FrameLayout(context) {

    /** Called when the card gains focus. */
    var onFocusGained: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        foreground = UiStyleProvider.tileFocusRing(context, tokens, radiusDp)
        val radius = UiStyleProvider.dp(context, radiusDp ?: tokens.tileRadiusDp).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) focusScale else 1f
        val z = if (gainFocus) UiStyleProvider.dp(context, 12).toFloat() else 0f
        animate().scaleX(scale).scaleY(scale).translationZ(z).setDuration(120).start()
        if (gainFocus) onFocusGained?.invoke()
    }

    fun resetFocusState() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        translationZ = 0f
    }
}

/** Focusable row (e.g. a year in the timeline) that reports focus changes. */
class FocusRowView(context: Context) : LinearLayout(context) {
    var onFocusGained: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onFocusGained?.invoke()
    }
}
