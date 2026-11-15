package nl.giejay.android.tv.immich.home

import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import nl.giejay.android.tv.immich.ui.UiStyleProvider

/**
 * Non-focusable group label of the side menu ("Library", "Collections").
 */
class SectionHeaderPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val tokens = UiStyleProvider.current()
        val label = TextView(context)
        label.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        label.isFocusable = false
        label.typeface = UiStyleProvider.titleTypeface(tokens)
        // mockup: 24px / weight 600, padding 0 20px 12px (28px top for the 2nd group)
        label.textSize = 12f
        label.setTextColor(tokens.dim)
        label.minimumWidth = UiStyleProvider.dp(context, tokens.sidebarWidthDp - 2 * SIDEBAR_PAD_H_DP)
        return ViewHolder(label)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val label = viewHolder.view as TextView
        val context = label.context
        val row = item as? Row
        label.text = row?.headerItem?.name
        // the first group label sits at the top, the next ones are separated from the rows above
        val first = (row?.headerItem?.id ?: -1L) == -1L
        label.setPadding(
            UiStyleProvider.dp(context, 10),
            UiStyleProvider.dp(context, if (first) 0 else 14),
            UiStyleProvider.dp(context, 10),
            UiStyleProvider.dp(context, 6)
        )
    }

    companion object {
        /** horizontal padding of the sidebar list (mockup: 28px) */
        const val SIDEBAR_PAD_H_DP = 14
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as TextView).text = null
    }
}
