package nl.giejay.android.tv.immich.card

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.VerticalGridView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lays the photo grid out as one continuous matrix of square cells: the number of columns follows
 * the width that is visible (menu shown: fewer columns, menu hidden: more), cells keep the same edge.
 * [onChanged] receives (columns, tile edge in px) whenever either changes.
 *
 * Leanback does not resize the content when the side menu opens: it slides it to the right by the
 * menu width, so the part under/after the menu is off screen. [menuShown] accounts for that.
 */
class PhotoGridLayout(
    private val gridView: VerticalGridView,
    private val tokens: UiTokens,
    private val sidePaddingDp: Int,
    private val onChanged: (columns: Int, tileSizePx: Int) -> Unit
) {
    private var lastWidth = -1
    private var columns = 0
    private var tileSize = 0

    /** True while the side menu is on screen next to the content. */
    var menuShown: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                lastWidth = -1
                gridView.post { apply() }
            }
        }

    fun attach() {
        val context = gridView.context
        val padH = UiStyleProvider.dp(context, sidePaddingDp)
        val gap = UiStyleProvider.dp(context, tokens.gridGapDp)
        // top padding = room for the focused tile's scale (taken from the header gap, see
        // VerticalCardGridFragment). Not clipped to padding (the focus ring needs the side room);
        // the dock around the grid clips, so scrolled rows never slide over the header
        gridView.setPadding(padH, focusRoomPx(gridView.context, tokens), padH, UiStyleProvider.dp(context, 24))
        gridView.clipToPadding = false
        gridView.clipChildren = false
        // Leanback's lb_vertical_grid is wrap_content + centered; the photo matrix fills the width
        (gridView.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.gravity = Gravity.START or Gravity.TOP
            gridView.layoutParams = it
        }
        // the item animator cancels the tiles' own focus scale animation when the column count
        // changes (menu shown / hidden), leaving a tile stuck at 1.14
        gridView.itemAnimator = null
        gridView.setHorizontalSpacing(gap)
        gridView.setVerticalSpacing(gap)

        gridView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width != oldRight - oldLeft || lastWidth < 0) {
                v.post { apply() }
            }
        }
    }

    companion object {
        /** Space the focused tile grows into above the first row: half of the scale increase. */
        fun focusRoomPx(context: android.content.Context, tokens: UiTokens): Int {
            val cell = UiStyleProvider.dp(context, tokens.gridCellDp)
            return (cell * (tokens.focusScale - 1f) / 2f + 0.5f).toInt() + UiStyleProvider.dp(context, 2f)
        }
    }

    private fun apply() {
        val width = gridView.width
        if (width <= 0 || width == lastWidth) return
        lastWidth = width
        val context = gridView.context
        val padH = UiStyleProvider.dp(context, sidePaddingDp)
        val gap = UiStyleProvider.dp(context, tokens.gridGapDp)
        val wantedCell = UiStyleProvider.dp(context, tokens.gridCellDp)
        val hidden = if (menuShown) UiStyleProvider.dp(context, tokens.sidebarWidthDp) else 0
        val available = width - hidden - 2 * padH
        val n = max(1, ((available + gap).toFloat() / (wantedCell + gap)).roundToInt())
        val size = (available - gap * (n - 1)) / n
        if (n != columns || size != tileSize) {
            columns = n
            tileSize = size
            gridView.setColumnWidth(size)
            gridView.setNumColumns(n)
            onChanged(n, size)
        }
    }
}
