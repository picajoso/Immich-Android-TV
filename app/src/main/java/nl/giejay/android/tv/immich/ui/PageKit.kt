package nl.giejay.android.tv.immich.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView

/** Small building blocks shared by the redesigned pages (pills, switches). */
object PageKit {

    /**
     * Styles [tv] as a fully rounded pill. Selected = text-colored pill with bg-colored text,
     * otherwise a surface pill. A focused pill gets the focus ring.
     */
    fun stylePill(tv: TextView, tokens: UiTokens, selected: Boolean) {
        val ctx = tv.context
        UiStyleProvider.applyBody(tv, tokens, 14f, if (selected) tokens.bg else tokens.text)
        val fill = if (selected) tokens.text else tokens.pillBg
        fun shape(ring: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiStyleProvider.dp(ctx, 500).toFloat()
            setColor(fill)
            if (ring) setStroke(UiStyleProvider.dp(ctx, 2f), tokens.focusRing)
        }
        tv.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(), shape(false))
        }
        tv.setPadding(
            UiStyleProvider.dp(ctx, 14),
            UiStyleProvider.dp(ctx, 7),
            UiStyleProvider.dp(ctx, 14),
            UiStyleProvider.dp(ctx, 7)
        )
    }

    fun pill(
        ctx: Context,
        tokens: UiTokens,
        text: String,
        selected: Boolean,
        focusable: Boolean,
        onClick: (() -> Unit)? = null
    ): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.maxLines = 1
        stylePill(tv, tokens, selected)
        tv.isFocusable = focusable
        tv.isFocusableInTouchMode = focusable
        if (onClick != null) {
            tv.isClickable = true
            tv.setOnClickListener { onClick() }
        }
        return tv
    }
}

/** On / off switch drawn with a canvas (no dependency on Material). */
class ToggleSwitchView(context: Context, private val tokens: UiTokens) : View(context) {
    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(UiStyleProvider.dp(context, 44), UiStyleProvider.dp(context, 26))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = if (checked) tokens.accent else 0x33FFFFFF
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)
        paint.color = Color.WHITE
        val r = h / 2f - UiStyleProvider.dp(context, 3)
        val cx = if (checked) w - h / 2f else h / 2f
        canvas.drawCircle(cx, h / 2f, r, paint)
    }
}
