package nl.giejay.android.tv.immich.card

import android.view.View
import androidx.leanback.widget.VerticalGridView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.ui.UiTokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lays the photo grid out as one continuous matrix of square cells: the number of columns follows
 * the available width (menu shown: fewer columns, menu hidden: more), cells are resized to fill the
 * row exactly. [onChanged] receives (columns, tile edge in px) whenever either changes.
 */
class PhotoGridLayout(
    private val gridView: VerticalGridView,
    private val tokens: UiTokens,
    private val onChanged: (columns: Int, tileSizePx: Int) -> Unit
) {
    private var lastWidth = -1
    private var columns = 0
    private var tileSize = 0

    fun attach() {
        val context = gridView.context
        val padH = UiStyleProvider.dp(context, tokens.contentPaddingHDp)
        val gap = UiStyleProvider.dp(context, tokens.gridGapDp)
        // room for the focus scale so the first row is not clipped by the header / dock
        gridView.setPadding(padH, UiStyleProvider.dp(context, 10), padH, UiStyleProvider.dp(context, 24))
        gridView.clipToPadding = false
        gridView.clipChildren = false
        gridView.setHorizontalSpacing(gap)
        gridView.setVerticalSpacing(gap)

        gridView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width != oldRight - oldLeft || lastWidth < 0) {
                v.post { apply() }
            }
        }
    }

    private fun apply() {
        val width = gridView.width
        if (width <= 0 || width == lastWidth) return
        lastWidth = width
        val context = gridView.context
        val padH = UiStyleProvider.dp(context, tokens.contentPaddingHDp)
        val gap = UiStyleProvider.dp(context, tokens.gridGapDp)
        val wantedCell = UiStyleProvider.dp(context, tokens.gridCellDp)
        val available = width - 2 * padH
        val n = max(1, ((available + gap).toFloat() / (wantedCell + gap)).roundToInt())
        val size = (available - gap * (n - 1)) / n
        if (n != columns || size != tileSize) {
            columns = n
            tileSize = size
            gridView.setNumColumns(n)
            onChanged(n, size)
        }
    }
}
