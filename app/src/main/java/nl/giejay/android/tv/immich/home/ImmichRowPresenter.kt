package nl.giejay.android.tv.immich.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import nl.giejay.android.tv.immich.ui.UiStyleProvider

/**
 * Presenter of one entry of the side menu. Styled by the active [UiStyle]: icon + label,
 * rounded highlight (Apple) / filled pill (Google TV) while focused.
 */
class ImmichRowPresenter : Presenter() {
    var editMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup): ImmichRowViewHolder {
        val context = parent.context
        val tokens = UiStyleProvider.current()
        val root: View = LayoutInflater.from(context)
            .inflate(R.layout.presenter_row, parent, false)

        val viewHolder = ImmichRowViewHolder(root)
        root.background = UiStyleProvider.sidebarRowBackground(context, tokens)
        if (tokens.style == UiStyle.GOOGLE_TV) {
            // mockup: 62px tall rows, 26px side padding, the focused one is a pill that hugs its content
            val padH = UiStyleProvider.dp(context, 13)
            root.setPadding(padH, 0, padH, 0)
            root.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiStyleProvider.dp(context, 31))
            root.minimumHeight = UiStyleProvider.dp(context, 31)
            (root as? LinearLayout)?.gravity = android.view.Gravity.CENTER_VERTICAL
            viewHolder.navIcon.layoutParams = (viewHolder.navIcon.layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = UiStyleProvider.dp(context, 15)
                height = UiStyleProvider.dp(context, 15)
                marginEnd = UiStyleProvider.dp(context, 11)
            }
            viewHolder.tvTitle.layoutParams = (viewHolder.tvTitle.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                weight = 0f
            }
        } else {
            val padH = UiStyleProvider.dp(context, 10)
            val padV = UiStyleProvider.dp(context, 7)
            root.setPadding(padH, padV, padH, padV)
            // full width highlight: Leanback wraps header views in a wrap_content frame
            root.minimumWidth = UiStyleProvider.dp(context, tokens.sidebarWidthDp - 2 * SectionHeaderPresenter.SIDEBAR_PAD_H_DP)
        }

        viewHolder.tvTitle.setTextColor(UiStyleProvider.sidebarRowTextColors(tokens))
        viewHolder.tvTitle.typeface = UiStyleProvider.bodyTypeface(tokens)
        viewHolder.tvTitle.textSize = if (tokens.style == UiStyle.GOOGLE_TV) 13.5f else tokens.bodySizeSp
        updateIconTint(viewHolder, tokens.style, false)
        viewHolder.count.typeface = UiStyleProvider.bodyTypeface(tokens)
        if (tokens.style == UiStyle.GOOGLE_TV) {
            // dim on the dark drawer, dark on the white pill of the focused row
            viewHolder.count.isDuplicateParentStateEnabled = true
            viewHolder.count.setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf((tokens.selectedRowText and 0x00FFFFFF) or 0xBF000000.toInt(), (tokens.selectedRowText and 0x00FFFFFF) or 0xBF000000.toInt(), tokens.dim)
            ))
            (viewHolder.count.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = UiStyleProvider.dp(context, 14)
        } else {
            viewHolder.count.setTextColor(tokens.dim)
        }

        // the title is the focusable view, the whole row (root) shows the highlight
        viewHolder.tvTitle.setOnFocusChangeListener { _, hasFocus ->
            root.isSelected = hasFocus
            viewHolder.tvTitle.typeface = if (hasFocus) UiStyleProvider.titleTypeface(tokens) else UiStyleProvider.bodyTypeface(tokens)
            updateIconTint(viewHolder, tokens.style, hasFocus)
        }
        return viewHolder
    }

    private fun updateIconTint(vh: ImmichRowViewHolder, style: UiStyle, focused: Boolean) {
        val tokens = UiStyleProvider.tokens(style)
        if (style == UiStyle.GOOGLE_TV) {
            // icons follow the label: dark while the pill is filled (focused / selected row)
            vh.navIcon.setColorFilter(if (focused || vh.root.isSelected) tokens.selectedRowText else tokens.text)
        } else {
            // Apple: icons keep the accent color
            vh.navIcon.setColorFilter(tokens.accent)
        }
        vh.icon.setColorFilter(tokens.dim)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val headerItem = if (item == null) null else (item as Row).headerItem
        val vh = viewHolder as ImmichRowViewHolder
        if (UiStyleProvider.current().style == UiStyle.GOOGLE_TV && vh.tag == null) {
            vh.tag = true
            vh.root.viewTreeObserver.addOnGlobalLayoutListener {
                updateIconTint(vh, UiStyle.GOOGLE_TV, vh.tvTitle.isFocused)
            }
        }
        vh.tvTitle.text = headerItem?.name?.let { nl.giejay.android.tv.immich.shared.util.Tr.t(it) }

        val count = headerItem?.name?.let { counts[it] }
        vh.count.text = count
        vh.count.visibility = if (count != null && !editMode) View.VISIBLE else View.GONE

        val iconRes = headerItem?.name?.let { ICONS[it] }
        if (iconRes != null) {
            vh.navIcon.setImageResource(iconRes)
            vh.navIcon.visibility = View.VISIBLE
        } else {
            vh.navIcon.visibility = View.GONE
        }

        if (editMode) {
            if (headerItem?.name == "Edit") {
                vh.tvTitle.text = nl.giejay.android.tv.immich.shared.util.Tr.t("Done")
            } else {
                vh.icon.visibility = View.VISIBLE
                if (PreferenceManager.itemInStringSet(headerItem?.name, HIDDEN_HOME_ITEMS)) {
                    vh.icon.setImageResource(R.drawable.closed_eye)
                } else {
                    vh.icon.setImageResource(R.drawable.visible_eye)
                }
            }
        } else {
            vh.icon.visibility = View.GONE
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = null
    }

    companion object {
        /** Name of the signed-in user, shown in the Google TV drawer. */
        var userName: String? = null

        /** Counter shown at the right of a menu entry, by entry name (e.g. "Photos" -> "4.812"). */
        val counts: MutableMap<String, String> = mutableMapOf()

        val ICONS: Map<String, Int> = mapOf(
            "Photos" to R.drawable.ic_nav_photos,
            "Albums" to R.drawable.ic_nav_albums,
            "People" to R.drawable.ic_nav_people,
            "Favorites" to R.drawable.ic_nav_favorites,
            "Recent" to R.drawable.ic_nav_recent,
            "Random" to R.drawable.ic_nav_random,
            "On this day" to R.drawable.ic_nav_on_this_day,
            "Seasonal" to R.drawable.ic_nav_seasonal,
            "Timeline" to R.drawable.ic_nav_timeline,
            "Folders" to R.drawable.ic_nav_folders,
            "Edit" to R.drawable.ic_nav_edit,
            "Settings" to R.drawable.ic_nav_settings
        )
    }
}
