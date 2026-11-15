package nl.giejay.android.tv.immich.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.UI_STYLE
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import nl.giejay.mediaslider.view.ViewerStyle

/**
 * Design tokens of one interface style (dark only). Values are dp / sp, colors are ARGB ints.
 * Mockup px at 1080p map to dp by dividing by 2.
 */
data class UiTokens(
    val style: UiStyle,
    val bg: Int,
    val sidebar: Int,
    val line: Int,
    val text: Int,
    val text2: Int,
    val dim: Int,
    val accent: Int,
    /** background of the focused/selected sidebar row */
    val selectedRow: Int,
    /** text color on top of [selectedRow] */
    val selectedRowText: Int,
    val focusRing: Int,
    val pillBg: Int,
    val tileRadiusDp: Float,
    /** corner radius of the focused (scaled) tile; Apple tiles are square until focused */
    val focusedTileRadiusDp: Float,
    val gridGapDp: Float,
    val focusScale: Float,
    /** wanted edge of a grid cell; the real column count is derived from the available width */
    val gridCellDp: Float,
    val sidebarWidthDp: Int,
    val contentPaddingHDp: Int,
    val contentPaddingTopDp: Int,
    /** top padding of a grid header while the menu is hidden */
    val contentPaddingTopCompactDp: Int,
    /** space between the header row and the grid (menu shown / hidden) */
    val headerGapDp: Int,
    val headerGapCompactDp: Int,
    val titleSizeSp: Float,
    /** title size while the menu is hidden */
    val titleCompactSizeSp: Float,
    val bodySizeSp: Float,
    val rowCornerDp: Float
)

/**
 * Single entry point for everything that depends on the chosen [UiStyle]: colors, dimensions,
 * fonts and small drawables. Fragments/presenters call [current] when they are created, so a style
 * change only needs an activity recreate (see MainActivity).
 */
object UiStyleProvider {

    private val APPLE_TOKENS = UiTokens(
        style = UiStyle.APPLE,
        bg = 0xFF000000.toInt(),
        sidebar = 0xFF161618.toInt(),
        line = 0x17FFFFFF,
        text = 0xFFF5F5F7.toInt(),
        text2 = 0xDBF5F5F7.toInt(),
        dim = 0xFFA1A1A6.toInt(),
        accent = 0xFF0A84FF.toInt(),
        selectedRow = 0x1AFFFFFF,
        selectedRowText = 0xFFF5F5F7.toInt(),
        focusRing = 0xFFFFFFFF.toInt(),
        pillBg = 0xFF1C1C1E.toInt(),
        tileRadiusDp = 0f,
        focusedTileRadiusDp = 3f,
        gridGapDp = 2f,
        focusScale = 1.14f,
        gridCellDp = 98f,
        sidebarWidthDp = 200,
        contentPaddingHDp = 32,
        contentPaddingTopDp = 30,
        contentPaddingTopCompactDp = 22,
        headerGapDp = 15,
        headerGapCompactDp = 13,
        titleSizeSp = 32f,
        titleCompactSizeSp = 22f,
        bodySizeSp = 13f,
        rowCornerDp = 7f
    )

    private val GOOGLE_TOKENS = UiTokens(
        style = UiStyle.GOOGLE_TV,
        bg = 0xFF17181B.toInt(),
        sidebar = 0xFA0E0F11.toInt(),
        line = 0x17FFFFFF,
        text = 0xFFE8E9EC.toInt(),
        text2 = 0xFFE8E9EC.toInt(),
        dim = 0xFFA9ADB5.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        // Google TV: the selected/focused row is a pill filled with the text color, text in bg color
        selectedRow = 0xFFE8E9EC.toInt(),
        selectedRowText = 0xFF17181B.toInt(),
        focusRing = 0xFFFFFFFF.toInt(),
        pillBg = 0xFF2B2C31.toInt(),
        tileRadiusDp = 6f,
        focusedTileRadiusDp = 6f,
        gridGapDp = 8f,
        focusScale = 1.09f,
        gridCellDp = 103f,
        sidebarWidthDp = 200,
        contentPaddingHDp = 40,
        contentPaddingTopDp = 26,
        contentPaddingTopCompactDp = 26,
        headerGapDp = 15,
        headerGapCompactDp = 15,
        titleSizeSp = 26f,
        titleCompactSizeSp = 26f,
        bodySizeSp = 13f,
        rowCornerDp = 31f
    )

    fun tokens(style: UiStyle): UiTokens = when (style) {
        UiStyle.APPLE -> APPLE_TOKENS
        UiStyle.GOOGLE_TV -> GOOGLE_TOKENS
    }

    /** Style currently stored in the preferences (read straight from SharedPreferences, never stale). */
    fun currentStyle(): UiStyle = UI_STYLE.getValue(PreferenceManager.sharedPreference)

    fun current(): UiTokens = tokens(currentStyle())

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    fun titleTypeface(t: UiTokens): Typeface = UiFonts.title(t.style)
    fun bodyTypeface(t: UiTokens): Typeface = UiFonts.body(t.style)

    /** Fully rounded background used by the date pill and similar chips. */
    fun pillBackground(context: Context, t: UiTokens): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 500).toFloat()
            setColor(t.pillBg)
        }

    /** Rounded background of a focused/selected sidebar row. Transparent when not focused. */
    fun sidebarRowBackground(context: Context, t: UiTokens): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, t.rowCornerDp).toFloat()
            setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(t.selectedRow))
            addState(intArrayOf(android.R.attr.state_selected), shape(t.selectedRow))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }
    }

    /** Text colors of a sidebar row: [selectedRowText] while focused, [text2] otherwise. */
    fun sidebarRowTextColors(t: UiTokens): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(t.selectedRowText, t.selectedRowText, t.text2)
        )

    /** Ring drawn on top of a focused tile. */
    fun tileFocusRing(context: Context, t: UiTokens): StateListDrawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, t.focusedTileRadiusDp).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 2f), t.focusRing)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    fun applyTitle(view: TextView, t: UiTokens, sizeSp: Float = t.titleSizeSp) {
        view.typeface = titleTypeface(t)
        view.textSize = sizeSp
        view.setTextColor(t.text)
        if (t.style == UiStyle.APPLE) view.letterSpacing = -0.025f
    }

    fun applyBody(view: TextView, t: UiTokens, sizeSp: Float = t.bodySizeSp, color: Int = t.text) {
        view.typeface = bodyTypeface(t)
        view.textSize = sizeSp
        view.setTextColor(color)
    }

    /** Look of the viewer pill and info panel (mediaslider module) for the active style. */
    fun viewerStyle(t: UiTokens = current()): ViewerStyle = when (t.style) {
        UiStyle.APPLE -> ViewerStyle(
            titleTypeface = titleTypeface(t),
            bodyTypeface = bodyTypeface(t)
        )
        UiStyle.GOOGLE_TV -> ViewerStyle(
            pillBg = 0xEB1F2024.toInt(),
            pillStroke = 0,
            pillText = 0xFFFFFFFF.toInt(),
            pillTextDim = t.dim,
            pillSeparator = t.dim,
            panelBg = 0xFF1F2024.toInt(),
            panelStroke = 0,
            panelWidthDp = 270,
            panelMarginDp = 0,
            panelCornerDp = 0f,
            panelPaddingHDp = 18,
            panelPaddingVDp = 26,
            panelHeading = true,
            text = t.text,
            dim = t.dim,
            iconTint = t.dim,
            cardBlocks = true,
            blockBg = t.pillBg,
            blockCornerDp = 9f,
            primaryBg = t.text,
            primaryText = t.bg,
            secondaryBg = t.pillBg,
            secondaryText = t.text,
            focusRing = 0,
            photoInsetDp = 0,
            buttonCornerDp = 36f,
            titleTypeface = titleTypeface(t),
            bodyTypeface = bodyTypeface(t)
        )
    }
}
