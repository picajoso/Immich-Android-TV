package nl.giejay.android.tv.immich.home

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DividerPresenter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import androidx.leanback.widget.SectionRow
import nl.giejay.android.tv.immich.album.AlbumsHeroFragment
import nl.giejay.android.tv.immich.assets.TimelineOverviewFragment
import nl.giejay.android.tv.immich.assets.AllAssetFragment
import nl.giejay.android.tv.immich.assets.FolderFragment
import nl.giejay.android.tv.immich.assets.RandomAssetsFragment
import nl.giejay.android.tv.immich.assets.RecentAssetsFragment
import nl.giejay.android.tv.immich.assets.SeasonalFragment
import nl.giejay.android.tv.immich.assets.TimelineFragment
import nl.giejay.android.tv.immich.onthisday.OnThisDayBrowseFragment
import nl.giejay.android.tv.immich.favorite.FavoritesBrowseFragment
import nl.giejay.android.tv.immich.people.PeopleFragment
import nl.giejay.android.tv.immich.settings.SettingsFragment
import nl.giejay.android.tv.immich.shared.fragment.GridFragment
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import nl.giejay.android.tv.immich.assets.IntegratedTimelineFragment
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class HomeFragment : BrowseSupportFragment() {
    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private lateinit var pageRows: Map<String, PageRow>
    private val isApple: Boolean get() = UiStyleProvider.current().style == UiStyle.APPLE
    private val profileRow = ProfileRow()
    private val sectionRows: List<SectionRow> by lazy {
        GROUPS.mapIndexed { index, group -> SectionRow(HeaderItem(-(index + 1).toLong(), group.first)) }
    }
    private var edgeHint: View? = null
    private var scrim: View? = null

    /** Google TV: the content is dimmed exactly while the drawer is open. */
    private fun syncScrim() {
        val dim = scrim ?: return
        dim.visibility = if (isShowingHeaders) View.VISIBLE else View.GONE
        dim.alpha = if (isShowingHeaders) 1f else 0f
    }

    /** The drawer container; only touched in the Google TV style. */
    private fun drawerDock(): View? =
        if (isApple) null else view?.findViewById(androidx.leanback.R.id.browse_headers_dock)
    private val settingsSpacer = SpacerRow()
    private var spacerListenerAdded = false
    val immichRowPresenter = ImmichRowPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("Loaded Home")

        setupUi()
        loadData()

        mainFragmentRegistry.registerFragment(PageRow::class.java, PageRowFragmentFactory())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spacerListenerAdded = false
        headersSupportFragment.setOnHeaderViewSelectedListener { _, row ->
            selectedPosition = row?.let { mRowsAdapter.indexOf(it) } ?: 0
        }

        setupEdgeHint(view)
        styleHeaders()

        loadMenuCounters()

        if (savedInstanceState == null) {
            // position 0 is a (non selectable) group label, start on the first page
            val firstPage = (0 until mRowsAdapter.size()).firstOrNull { mRowsAdapter.get(it) is PageRow } ?: 0
            setSelectedPosition(firstPage, false)
        }

        headersSupportFragment.setOnHeaderClickedListener { _, _ ->
            if (!this.isInHeadersTransition) {
                this.startHeadersTransition(false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncScrim()
        // BrowseSupportFragment.onStart re-applies its own header alignment, override it again
        styleHeaders()
        if (!spacerListenerAdded) {
            headersSupportFragment?.verticalGridView?.let { grid ->
                spacerListenerAdded = true
                grid.viewTreeObserver.addOnGlobalLayoutListener { adjustSettingsSpacer(grid) }
            }
        }
    }

    /**
     * Lays the side menu out like the mockup (nav: padding 64px 28px, rows 4px apart) instead of the
     * stock Leanback headers (56dp start padding, 21dp spacing, selected row pinned at 167dp).
     */
    private fun styleHeaders() {
        val grid = headersSupportFragment?.verticalGridView ?: return
        val context = grid.context
        val tokens = UiStyleProvider.current()
        // mockup nav padding: Apple 64px 28px, Google TV 56px 28px 40px 40px
        if (isApple) {
            val padH = UiStyleProvider.dp(context, SectionHeaderPresenter.SIDEBAR_PAD_H_DP)
            val padV = UiStyleProvider.dp(context, 32)
            grid.setPadding(padH, padV, padH, padV)
        } else {
            grid.setPadding(UiStyleProvider.dp(context, 20), UiStyleProvider.dp(context, 28), UiStyleProvider.dp(context, 14), UiStyleProvider.dp(context, 20))
        }
        grid.clipToPadding = false
        grid.setVerticalSpacing(UiStyleProvider.dp(context, 2))
        grid.layoutParams = grid.layoutParams.apply { width = UiStyleProvider.dp(context, tokens.sidebarWidthDp) }
        // the whole menu fits on screen: keep it still instead of scrolling the focused row to a keyline
        grid.windowAlignment = androidx.leanback.widget.BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        grid.windowAlignmentOffset = 0
        grid.windowAlignmentOffsetPercent = 50f
        grid.itemAlignmentOffset = 0
        grid.itemAlignmentOffsetPercent = 50f
        (grid.parent as? View)?.let { root ->
            root.setPadding(0, 0, 0, 0)
            root.findViewById<View>(androidx.leanback.R.id.fade_out_edge)?.visibility = View.GONE
        }
    }

    /**
     * Apple style pins Settings to the bottom of the menu (mockup: margin-top:auto). The menu is a
     * Leanback list, so an empty [SpacerRow] before Settings takes up the remaining height.
     */
    private fun adjustSettingsSpacer(grid: androidx.leanback.widget.VerticalGridView) {
        if (!::mRowsAdapter.isInitialized) return
        val spacerIndex = (0 until mRowsAdapter.size()).firstOrNull { mRowsAdapter.get(it) is SpacerRow } ?: return
        if (grid.childCount != mRowsAdapter.size()) return // not everything laid out yet
        var used = 0
        var spacerView: View? = null
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (grid.getChildAdapterPosition(child) == spacerIndex) {
                spacerView = child
            } else {
                used += child.height
            }
        }
        val spacer = spacerView ?: return
        val spacing = UiStyleProvider.dp(grid.context, 2) * (grid.childCount - 1)
        val wanted = (grid.height - grid.paddingTop - grid.paddingBottom - used - spacing).coerceAtLeast(0)
        if (spacer.layoutParams.height != wanted) {
            spacer.layoutParams = spacer.layoutParams.apply { height = wanted }
        }
    }

    /** Fills the counters of the menu (Photos: number of items in the library). */
    private fun loadMenuCounters() {
        val client = nl.giejay.android.tv.immich.shared.util.currentApiClient() ?: return
        if (!isApple) {
            viewLifecycleOwner.lifecycleScope.launch {
                val me = withContext(Dispatchers.IO) { client.currentUser() }
                me.getOrNull()?.name?.takeIf { it.isNotBlank() }?.let { name ->
                    ImmichRowPresenter.userName = name
                    val index = mRowsAdapter.indexOf(profileRow)
                    if (index >= 0) mRowsAdapter.notifyArrayItemRangeChanged(index, 1)
                }
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { client.assetStatistics() }
            stats.getOrNull()?.let {
                val text = nl.giejay.android.tv.immich.ui.UiFormat.count(it.total)
                ImmichRowPresenter.counts["Photos"] = text
                pageRows["Photos"]?.let { row ->
                    val index = mRowsAdapter.indexOf(row)
                    if (index >= 0) mRowsAdapter.notifyArrayItemRangeChanged(index, 1)
                }
            }
        }
    }

    /** Switches the content to another page of the menu (e.g. Timeline / Folders toggle). */
    fun selectPage(name: String) {
        val row = pageRows[name] ?: return
        val position = mRowsAdapter.indexOf(row)
        if (position >= 0) setSelectedPosition(position, true)
    }

    /** Rebuilds the menu after the Edit page changed which sections are visible. */
    private fun refreshMenu() {
        rebuildRows()
        val editPosition = (0 until mRowsAdapter.size()).firstOrNull { (mRowsAdapter.get(it) as? Row)?.headerItem?.name == "Edit" }
        if (editPosition != null) setSelectedPosition(editPosition, false)
    }

    private fun setupUi() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        // sidebar background of the active interface style
        brandColor = UiStyleProvider.current().sidebar
        // every page draws its own title: Leanback's one (top right, animated over the content)
        // stays empty
        title = ""

        val sHeaderPresenter: PresenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(ProfileRow::class.java, ProfilePresenter())
            .addClassPresenter(SpacerRow::class.java, SpacerPresenter())
            .addClassPresenter(
                SectionRow::class.java,
                SectionHeaderPresenter()
            )
            .addClassPresenter(Row::class.java, immichRowPresenter)

        setHeaderPresenterSelector(sHeaderPresenter)
    }

    private fun loadData() {
        mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = mRowsAdapter
        pageRows = createRows()
        rebuildRows()
    }

    private fun createRows(): Map<String, PageRow> {
        return HEADERS.mapIndexed { index, header -> header.name to PageRow(HeaderItem(index.toLong(), header.name)) }.toMap()
    }

    /**
     * Fills the side menu: group labels followed by their entries, Settings last.
     * In edit mode all entries (except Settings) are listed so they can be hidden/shown.
     */
    private fun rebuildRows() {
        fun visible(name: String): Boolean =
            immichRowPresenter.editMode || !PreferenceManager.itemInStringSet(name, HIDDEN_HOME_ITEMS)

        val items = mutableListOf<Row>()
        if (!isApple) items.add(profileRow)
        GROUPS.forEachIndexed { index, group ->
            val groupRows = group.second.filter { visible(it) }.mapNotNull { pageRows[it] }
            if (groupRows.isNotEmpty()) {
                // Google TV has one flat list, Apple labels its groups
                if (group.first.isNotEmpty()) items.add(sectionRows[index])
                items.addAll(groupRows)
            }
        }
        if (!immichRowPresenter.editMode) {
            items.add(settingsSpacer)
            pageRows["Settings"]?.let { items.add(it) }
        }
        mRowsAdapter.clear()
        mRowsAdapter.addAll(0, items)
    }

    /** Thin tab at the left edge, shown while the menu is hidden, hinting that left reopens it. */
    private fun setupEdgeHint(root: View) {
        val frame = root as? FrameLayout ?: return
        val tokens = UiStyleProvider.current()
        val context = root.context
        val hint = View(context)
        // mockup: 10px x 120px tab, 8px radius on the right, --sel color
        hint.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = UiStyleProvider.dp(context, 4).toFloat()
            // rounded on the right side only
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            setColor(if (tokens.style == nl.giejay.android.tv.immich.shared.prefs.UiStyle.APPLE) tokens.selectedRow else (tokens.dim and 0x00FFFFFF) or (0x99 shl 24))
        }
        // Google TV: the drawer overlays the content, which is dimmed behind it
        if (!isApple) {
            // not scale_frame: Leanback replaces its children whenever the page changes
            (root.findViewById<View>(androidx.leanback.R.id.browse_container_dock) as? ViewGroup)?.let { frame2 ->
                val dim = View(context)
                dim.setBackgroundColor(0x99000000.toInt())
                dim.alpha = 0f
                dim.visibility = View.GONE
                dim.isFocusable = false
                dim.elevation = 0f // the drawer (elevated) stays above
                frame2.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                scrim = dim
                // the menu starts open: the content is dimmed from the beginning
                root.post { syncScrim() }
            }
        }
        val lp = FrameLayout.LayoutParams(UiStyleProvider.dp(context, if (isApple) 5 else 4), UiStyleProvider.dp(context, 60))
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        hint.visibility = View.GONE
        frame.addView(hint, lp)
        edgeHint = hint
        setBrowseTransitionListener(object : BrowseTransitionListener() {
            override fun onHeadersTransitionStart(withHeaders: Boolean) {
                if (withHeaders) {
                    hint.visibility = View.GONE
                    scrim?.animate()?.alpha(1f)?.setDuration(150)?.withStartAction { scrim?.visibility = View.VISIBLE }?.start()
                    // Google TV: the drawer is shown / hidden by us (it no longer slides the content)
                    drawerDock()?.visibility = View.VISIBLE
                    if (MenuState.dirty) {
                        MenuState.dirty = false
                        refreshMenu()
                    }
                }
            }

            override fun onHeadersTransitionStop(withHeaders: Boolean) {
                if (!withHeaders) scrim?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { scrim?.visibility = View.GONE }?.start()
                if (!withHeaders) drawerDock()?.visibility = View.INVISIBLE
                hint.visibility = if (withHeaders) View.GONE else View.VISIBLE
            }
        })
    }

    private class PageRowFragmentFactory : FragmentFactory<Fragment>() {
        override fun createFragment(rowObj: Any): Fragment {
            val row = rowObj as Row
            Timber.i("Going to show page: ${row.headerItem.name}")
            return HEADERS[row.headerItem.id.toInt()].fragment()
        }
    }

    companion object {
        /** Apple: two labelled groups, Settings is appended after them. */
        private val APPLE_GROUPS: List<Pair<String, List<String>>> = listOf(
            "Library" to listOf("Photos", "Albums", "People", "Favorites", "Recent", "Random"),
            "Collections" to listOf("On this day", "Seasonal", "Timeline", "Folders", "Edit")
        )

        /** Google TV: one flat list (no group labels), in the order of the mockup. */
        private val GOOGLE_GROUPS: List<Pair<String, List<String>>> = listOf(
            "" to listOf("Photos", "Albums", "Random", "People", "Recent", "Favorites", "On this day", "Seasonal", "Timeline", "Folders", "Edit")
        )

        private val GROUPS: List<Pair<String, List<String>>>
            get() = if (UiStyleProvider.current().style == UiStyle.APPLE) APPLE_GROUPS else GOOGLE_GROUPS

        /** Every section of the menu in display order, Edit included, Settings excluded. */
        val MENU_ENTRIES: List<String> get() = GROUPS.flatMap { it.second }

        private val HEADERS: List<Header> = listOf(
            Header("Albums") { AlbumsHeroFragment() },
            Header("Photos") { AllAssetFragment() },
            Header("Random") { RandomAssetsFragment() },
            Header("People") { PeopleFragment() },
            Header("Recent") { RecentAssetsFragment() },
            Header("Favorites") { FavoritesBrowseFragment() },
            Header("On this day") { OnThisDayBrowseFragment() }, // CAMBIADO AQUÍ
            Header("Seasonal") { SeasonalFragment() },
            Header("Timeline") { TimelineOverviewFragment() },
            Header("Folders") { FolderFragment() },
            Header("Edit") { EditMenuFragment() },
            Header("Settings") { SettingsFragment() },
        )
    }
}

class Header(val name: String, var show: Boolean = false, val fragment: () -> Fragment)

/** Empty, non focusable entry of the side menu used to push Settings to the bottom. */
class SpacerRow : Row(HeaderItem(-100L, "")) {
    // like SectionRow / PageRow: only lives in the menu, never rendered as a content row
    override fun isRenderedAsRowView(): Boolean = false
}

class SpacerPresenter : androidx.leanback.widget.Presenter() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
        val view = View(parent.context)
        view.isFocusable = false
        view.layoutParams = android.view.ViewGroup.LayoutParams(1, 0)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {}
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

/** Signed-in user at the top of the Google TV drawer (avatar with the initial + name). */
class ProfileRow : Row(HeaderItem(-300L, "")) {
    override fun isRenderedAsRowView(): Boolean = false
}

class ProfilePresenter : androidx.leanback.widget.Presenter() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
        val ctx = parent.context
        val tokens = UiStyleProvider.current()
        val row = android.widget.LinearLayout(ctx)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.isFocusable = false
        row.setPadding(UiStyleProvider.dp(ctx, 13), 0, UiStyleProvider.dp(ctx, 13), UiStyleProvider.dp(ctx, 14))
        row.layoutParams = android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        val avatar = android.widget.TextView(ctx)
        avatar.gravity = android.view.Gravity.CENTER
        avatar.includeFontPadding = false
        avatar.textSize = 12f
        avatar.typeface = UiStyleProvider.titleTypeface(tokens)
        avatar.setTextColor(0xFF1B1206.toInt())
        avatar.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFE8A33D.toInt())
        }
        row.addView(avatar, android.widget.LinearLayout.LayoutParams(UiStyleProvider.dp(ctx, 24), UiStyleProvider.dp(ctx, 24)))
        val name = android.widget.TextView(ctx)
        name.includeFontPadding = false
        UiStyleProvider.applyBody(name, tokens, 13f, tokens.dim)
        row.addView(name, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UiStyleProvider.dp(ctx, 8)
        })
        row.tag = arrayOf<Any>(avatar, name)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        @Suppress("UNCHECKED_CAST")
        val parts = viewHolder.view.tag as Array<Any>
        val user = ImmichRowPresenter.userName
        (parts[0] as android.widget.TextView).text = user?.firstOrNull()?.uppercase() ?: ""
        (parts[1] as android.widget.TextView).text = user ?: ""
        viewHolder.view.visibility = if (user == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
