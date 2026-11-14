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
        label.textSize = tokens.bodySizeSp
        label.setTextColor(tokens.dim)
        label.setPadding(
            UiStyleProvider.dp(context, 10),
            UiStyleProvider.dp(context, 12),
            UiStyleProvider.dp(context, 10),
            UiStyleProvider.dp(context, 4)
        )
        return ViewHolder(label)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = (item as? Row)?.headerItem?.name
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as TextView).text = null
    }
}
