package nl.giejay.android.tv.immich.shared.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.app.BackgroundManager
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenterSelector
import nl.giejay.android.tv.immich.card.PhotoGridLayout
import nl.giejay.android.tv.immich.card.PhotoTilePresenter
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import java.util.Date
import java.util.Locale
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.LOAD_BACKGROUND_IMAGE
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.util.Debouncer
import nl.giejay.android.tv.immich.shared.viewmodel.KeyEventsViewModel
import nl.giejay.android.tv.immich.shared.prefs.GRID_COLUMN_COUNT
import nl.giejay.android.tv.immich.shared.cache.FavoriteCache
import timber.log.Timber
import java.util.concurrent.TimeUnit


abstract class VerticalCardGridFragment<ITEM> : GridFragment() {
    protected var assets: List<ITEM> = emptyList()
    protected val startPage = 1

    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundManager: BackgroundManager? = null

    protected lateinit var apiClient: ApiClient
    private lateinit var keyEvents: KeyEventsViewModel
    private val ZOOM_FACTOR = FocusHighlight.ZOOM_FACTOR_SMALL
    protected val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private val mainScope = CoroutineScope(Job() + Dispatchers.Main)
    private val assetsStillToRender: MutableList<ITEM> = mutableListOf()
    protected var currentPage: Int = startPage
    protected var allPagesLoaded: Boolean = false
    private var currentLoadingJob: Job? = null
    protected val selectionMode: Boolean
        get() = arguments?.getBoolean("selectionMode", false) ?: false
    private var currentSelectedIndex: Int = 0

    /**
     * Photo grids (Photos, Random, Recent, Favorites...) opt in to the redesigned layout: header with
     * title + date pill, continuous grid of square tiles with width-driven column count.
     */
    protected open val usePhotoTiles: Boolean get() = false

    /** Title shown in the header of a photo grid. */
    protected open val photoGridTitle: String get() = ""

    override val hideBrandedTitle: Boolean get() = usePhotoTiles

    private var photoTilePresenter: PhotoTilePresenter? = null
    private var datePill: TextView? = null

    /** Number of columns of the photo grid; follows the width available (menu shown / hidden). */
    protected var currentColumns: Int = COLUMNS
        private set

    abstract fun sortItems(items: List<ITEM>): List<ITEM>
    abstract suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<ITEM>>

    abstract fun createCard(a: ITEM): Card
    abstract fun getBackgroundPicture(it: ITEM): String?
    open fun setTitle(response: List<ITEM>) {
        // default no title
    }

    abstract fun onItemSelected(card: Card, indexOf: Int)
    abstract fun onItemClicked(card: Card)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PreferenceManager.isLoggedId()) {
            apiClient =
                ApiClient.getClient(
                    ApiClientConfig(
                        PreferenceManager.get(HOST_NAME),
                        PreferenceManager.get(API_KEY),
                        PreferenceManager.get(DISABLE_SSL_VERIFICATION),
                        PreferenceManager.get(DEBUG_MODE)
                    )
                )
        } else {
            Toast.makeText(
                ImmichApplication.appContext,
                "Invalid Immich server settings, redirecting to login screen.",
                Toast.LENGTH_SHORT
            ).show()
            findNavController().navigate(HomeFragmentDirections.actionGlobalSignInFragment())
            return
        }

        keyEvents = ViewModelProvider(requireActivity())[KeyEventsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                keyEvents.state.collect {
                    // open popup menu on the right side if its the last photo in the row and user presses right button
                    if (it?.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && ((adapter.size() == 0 && allPagesLoaded) || currentSelectedIndex > 0 && (currentSelectedIndex % currentColumns == currentColumns - 1 || currentSelectedIndex + 1 == adapter.size()))) {
                        openPopUpMenu()
                    } else if (it?.keyCode == KeyEvent.KEYCODE_FORWARD || it?.keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD || it?.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        updateManualPositionHandler(adapter.size() - 1)
                    } else if (it?.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || it?.keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD) {
                        updateManualPositionHandler((currentSelectedIndex - FETCH_PAGE_COUNT).coerceAtLeast(0))
                    }
                }
            }
        }

        setupAdapter()
        setupBackgroundManager()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card: Card = item as Card
            if (selectionMode) {
                card.selected = !card.selected
                adapter.notifyArrayItemRangeChanged(currentSelectedIndex, 1)
                onItemSelected(card, currentSelectedIndex)
            } else {
                onItemClicked(card)
            }
        }
        setOnItemViewSelectedListener { _, item, _, _ ->
            currentSelectedIndex = adapter.indexOf(item)
            if (item is Card) {
                onCardFocused(item, currentSelectedIndex)
            }
            item?.let {
                loadBackgroundDebounced((it as Card).backgroundUrl) {
                    loadBackgroundDebounced(it.thumbnailUrl) {
                        Timber.tag(javaClass.name)
                            .e("Could not load background url")
                    }
                }
            }
            with(this@VerticalCardGridFragment) {
                loadNextItemsIfNeeded(adapter.indexOf(item))
            }
        }
        // fetch initial items
        fetchInitialItems()
    }

    protected open fun fetchInitialItems() {
        ioScope.launch {
            loadData().fold(
                { itLeft -> showErrorMessage(itLeft) },
                { itRight ->
                    val assets = filterItems(itRight)
                    setupViews(assets)
                    if (assets.size < fetchCount) {
                        // immediately load next assets
                        currentLoadingJob = fetchNextItems()
                    }
                }
            )
        }
    }

    private fun loadNextItemsIfNeeded(selectedIndex: Int) {
        if (selectedIndex != -1 && (adapter.size() - selectedIndex < FETCH_NEXT_THRESHOLD)) {
            if (currentLoadingJob?.isActive != true && assetsStillToRender.isEmpty() && !allPagesLoaded) {
                // try a next page if its available
                currentLoadingJob = fetchNextItems()
            } else {
                mainScope.launch {
                    addAssetsPaginated()
                }
            }
        }
    }

    protected open fun resortItems() {
        assets = sortItems(assets)
        adapter.clear()
        adapter.addAll(0, assets.map { createCard(it) })
    }

    protected open fun clearState() {
        currentPage = startPage
        assets = emptyList()
        assetsStillToRender.clear()
        adapter.clear()
    }

    protected open fun openPopUpMenu() {
        // to implement by children
    }

    private fun fetchNextItems(): Job {
        return ioScope.launch {
            val nextAssets = loadMoreAssets()
            setDataOnMain(nextAssets)
        }
    }

    protected open fun filterItems(items: List<ITEM>): List<ITEM> = items

    protected open suspend fun loadMoreAssets(): List<ITEM> {
        if (allPagesLoaded) {
            return emptyList()
        }
        currentPage += 1
        return loadData().fold(
            { errorMessage ->
                showErrorMessage(errorMessage)
                emptyList()
            },
            { items ->
                Timber.i("Loading next items, ${items.size}")
                val filteredItems = filterItems(items)
                allPagesLoaded = allPagesLoaded(items)
                if (filteredItems.size < fetchCount) {
                    return filteredItems + loadMoreAssets()
                } else  {
                    return filteredItems
                }
            }
        )
    }

    protected open fun allPagesLoaded(items: List<ITEM>): Boolean =
        items.size < FETCH_PAGE_COUNT

    override fun onResume() {
        super.onResume()
        if (assets.isNotEmpty()) {
            progressBar?.visibility = View.GONE
        }
    }

    private fun setupAdapter() {
        if (usePhotoTiles) {
            setupPhotoTileAdapter()
            return
        }
        val presenter = VerticalGridPresenter(ZOOM_FACTOR)
        
        val cols = try {
            PreferenceManager.get(nl.giejay.android.tv.immich.shared.prefs.GRID_COLUMN_COUNT)
        } catch (e: Exception) { 4 }
        presenter.numberOfColumns = cols
        
        gridPresenter = presenter
        
        // INSTANCIAMOS EL PRESENTER
        val cardPresenter = nl.giejay.android.tv.immich.card.CardPresenter(requireContext())
        
        // CONFIGURAMOS SOLO EL LONG CLICK (El short click lo maneja onItemViewClickedListener)
        cardPresenter.onLongClick = { card ->
            if (card is Card) {
                toggleFavorite(card)
            }
        }

        // IMPORTANTE: NO asignar onShortClick aquí, porque CardPresenter no lo tiene
        // y Leanback ya maneja el clic normal.

        adapter = ArrayObjectAdapter(cardPresenter)
    }

    private fun setupPhotoTileAdapter() {
        val tokens = UiStyleProvider.current()
        // no zoom / dimmer / default shadow from Leanback: the tile does its own focus effect
        val presenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false)
        presenter.shadowEnabled = false
        presenter.numberOfColumns = COLUMNS // replaced by the width-driven count in PhotoGridLayout
        gridPresenter = presenter

        val tiles = PhotoTilePresenter(requireContext(), tokens)
        tiles.onLongClick = { card ->
            if (card is Card) {
                toggleFavorite(card)
            }
        }
        photoTilePresenter = tiles
        adapter = ArrayObjectAdapter(tiles)
    }

    override fun onGridViewCreated(gridView: VerticalGridView) {
        super.onGridViewCreated(gridView)
        if (!usePhotoTiles) return
        val tokens = UiStyleProvider.current()
        photoGridLayout = PhotoGridLayout(gridView, tokens) { columns, tileSize ->
            currentColumns = columns
            photoTilePresenter?.let {
                it.tileSizePx = tileSize
                // re-bind so existing tiles take the new size
                if (adapter.size() > 0) {
                    adapter.notifyArrayItemRangeChanged(0, adapter.size())
                }
            }
        }.also {
            it.menuShown = menuShown
            it.attach()
        }
    }

    private var photoGridLayout: PhotoGridLayout? = null
    private var menuShown: Boolean = true

    override fun onMenuVisibilityChanged(menuShown: Boolean) {
        this.menuShown = menuShown
        if (!usePhotoTiles) return
        photoGridLayout?.menuShown = menuShown
        view?.let { layoutPhotoHeader(it) }
    }

    /**
     * Header of the photo grid. Menu shown (mockup ①): 60px top, 64px title, 30px below.
     * Menu hidden (mockup ②): 44px top, 44px title, 26px below. The content is as wide as the
     * screen and slides right under the menu, so the right padding adds the menu width.
     */
    private fun layoutPhotoHeader(root: View) {
        val tokens = UiStyleProvider.current()
        val context = root.context
        val header = root.findViewById<View>(R.id.grid_header) ?: return
        val title = root.findViewById<TextView>(R.id.grid_title) ?: return
        val compact = !menuShown
        val hidden = if (menuShown) UiStyleProvider.dp(context, tokens.sidebarWidthDp) else 0
        header.setPadding(
            UiStyleProvider.dp(context, tokens.contentPaddingHDp),
            UiStyleProvider.dp(context, if (compact) tokens.contentPaddingTopCompactDp else tokens.contentPaddingTopDp),
            UiStyleProvider.dp(context, tokens.contentPaddingHDp) + hidden,
            (UiStyleProvider.dp(context, if (compact) tokens.headerGapCompactDp else tokens.headerGapDp) -
                PhotoGridLayout.focusRoomPx(context, tokens)).coerceAtLeast(0)
        )
        title.textSize = if (compact) tokens.titleCompactSizeSp else tokens.titleSizeSp
        lineHeightOne(title)
    }

    /** CSS line-height:1 — the text box is as tall as the font size (negative half-leading). */
    private fun lineHeightOne(view: TextView) {
        val fm = view.paint.fontMetricsInt
        val extra = (fm.descent - fm.ascent) - view.textSize.toInt()
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.topMargin = -extra / 2
        lp.bottomMargin = -(extra - extra / 2)
        view.layoutParams = lp
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (usePhotoTiles) {
            setupPhotoHeader(view)
        }
    }

    private fun setupPhotoHeader(root: View) {
        val tokens = UiStyleProvider.current()
        val context = root.context
        val header = root.findViewById<View>(R.id.grid_header) ?: return
        val title = root.findViewById<TextView>(R.id.grid_title)
        val pill = root.findViewById<TextView>(R.id.grid_date_pill)

        header.visibility = View.VISIBLE
        title.text = photoGridTitle
        UiStyleProvider.applyTitle(title, tokens)
        // line-height 1 like the mockup's h2
        title.includeFontPadding = false
        pill.includeFontPadding = false
        layoutPhotoHeader(root)

        UiStyleProvider.applyBody(pill, tokens, tokens.bodySizeSp, tokens.dim)
        pill.background = UiStyleProvider.pillBackground(context, tokens)
        pill.setPadding(
            UiStyleProvider.dp(context, 12),
            UiStyleProvider.dp(context, 6),
            UiStyleProvider.dp(context, 12),
            UiStyleProvider.dp(context, 6)
        )
        datePill = pill
    }

    /** Shows a small pill-shaped action button in the header of a photo grid (e.g. "Shuffle"). */
    protected fun setHeaderAction(text: String, onClick: () -> Unit) {
        val root = view ?: return
        val actions = root.findViewById<View>(R.id.grid_header_actions) ?: return
        val button = root.findViewById<TextView>(R.id.grid_action_next) ?: return
        nl.giejay.android.tv.immich.ui.PageKit.stylePill(button, UiStyleProvider.current(), selected = false)
        button.text = text
        button.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        button.setOnClickListener { onClick() }
    }

    /** True while the date pill has no text yet. */
    protected fun isDatePillEmpty(): Boolean = datePill?.text.isNullOrEmpty()

    /** Called whenever the focused card changes; photo grids update their date pill here. */
    protected open fun onCardFocused(card: Card, index: Int) {
        // default: nothing
    }

    /**
     * Shows "Wed, Nov 12, 2026 · City" in the header pill. Text only: no layout work besides the
     * pill itself. The city part is omitted when unknown.
     */
    protected fun updateDatePill(date: Date?, city: String?, timeZoneId: String? = null) {
        val pill = datePill ?: return
        if (date == null) {
            pill.text = ""
            pill.visibility = View.INVISIBLE
            return
        }
        val tokens = UiStyleProvider.current()
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMdyyyy")
        val format = java.text.SimpleDateFormat(pattern, locale)
        timeZoneId?.takeIf { it.isNotBlank() }?.let { id ->
            // unknown ids make TimeZone.getTimeZone return GMT, so only accept known ones
            if (java.util.TimeZone.getAvailableIDs().contains(id)) format.timeZone = java.util.TimeZone.getTimeZone(id)
        }
        val dateText = format.format(date)
        val text = SpannableStringBuilder(dateText)
        text.setSpan(ForegroundColorSpan(tokens.text), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!city.isNullOrBlank()) {
            text.append("  \u00B7  ").append(city)
        }
        pill.text = text
        pill.visibility = View.VISIBLE
    }

    private fun setupBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(activity)
        if (!mBackgroundManager!!.isAttached) {
            mBackgroundManager!!.attach(requireActivity().window)
        }
        mBackgroundManager!!.setColor(UiStyleProvider.current().bg)
        mMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(mMetrics)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (mBackgroundManager?.isAttached == true) {
            mBackgroundManager?.drawable = null
        }
    }

    private suspend fun setupViews(assets: List<ITEM>) =
        withContext(Dispatchers.Main) {
            progressBar?.visibility = View.GONE
            setTitle(assets)
            assets.firstOrNull()?.let { loadBackground(getBackgroundPicture(it)) {} }
            setDataOnMain(assets)
        }

    protected suspend fun setDataOnMain(assets: List<ITEM>) = withContext(Dispatchers.Main) {
        setData(assets)
    }

    protected open fun setData(assets: List<ITEM>) {
        val sortedItems = sortItems(assets.filter { !this@VerticalCardGridFragment.assets.contains(it) })
        this@VerticalCardGridFragment.assets += sortedItems
        assetsStillToRender.addAll(sortedItems)
        addAssetsPaginated()
    }

    protected open suspend fun loadData(): Either<String, List<ITEM>> {
        return loadItems(apiClient, currentPage, FETCH_PAGE_COUNT)
    }

    private fun loadBackgroundDebounced(backgroundUrl: String?, onLoadFailed: () -> Unit) {
        // the continuous photo grid has no room for a blurred background behind the tiles
        if (!usePhotoTiles && PreferenceManager.get(LOAD_BACKGROUND_IMAGE)) {
            Debouncer.debounce("background", { loadBackground(backgroundUrl, onLoadFailed) }, 1, TimeUnit.SECONDS)
        }
    }

    private fun loadBackground(backgroundUrl: String?, onLoadFailed: () -> Unit) {
        if (!isAdded || usePhotoTiles || !PreferenceManager.get(LOAD_BACKGROUND_IMAGE)) {
            return
        }
        if (backgroundUrl.isNullOrEmpty()) {
            Timber.i("Could not load background because background url is null")
            return
        }
        val width: Int = mMetrics.widthPixels
        val height: Int = mMetrics.heightPixels
        try {
            Glide.with(requireActivity())
                .load(backgroundUrl)
                .centerCrop()
                .into(object : SimpleTarget<Drawable?>(width, height) {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable?>?
                    ) {
                        try {
                            mBackgroundManager?.drawable = resource
                        } catch (e: Exception) {
                            Timber.e(e, "Could not set background")
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        onLoadFailed()
                    }
                })
        } catch (e: Exception) {
            Timber.e(e, "Could not fetch background")
        }
    }

    private fun addAssetsPaginated() {
        val assetsPaginated = assetsStillToRender.take(fetchCount)
        val cards = assetsPaginated.map { createCard(it) }
        adapter.addAll(adapter.size(), cards)
        assetsStillToRender.removeAll(assetsPaginated)
    }

    private suspend fun showErrorMessage(message: String) = withContext(Dispatchers.Main) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG)
                .show()
        }
        Timber.e(message)
    }

    // FUNCIÓN AUXILIAR PARA FOTOS FAVORITS - EN LA CLASE (puede ser al final):
    private fun toggleFavorite(card: Card) {
        ioScope.launch {
            // 1. Calculamos el estado opuesto (si era fav, ahora no, y viceversa)
            val newStatus = !card.isFavorite
            
            // 2. Llamamos al servidor de forma segura
            val result = apiClient.toggleFavorite(card.id, newStatus)
            
            result.fold(
                { error -> showErrorMessage("Error updating favorite: $error") },
                { _ -> 
                    // 3. Éxito: Actualizamos la UI inmediatamente
                    withContext(Dispatchers.Main) {
                        card.isFavorite = newStatus

                        // AÑADIDO: Guardar en cache global
                        FavoriteCache.overrides[card.id] = newStatus
                        Timber.d("VerticalCardGrid: Guardado en FavoriteCache: ${card.id} -> $newStatus")
                    

                        // Avisamos al adaptador para que repinte la estrella
                        val index = adapter.indexOf(card)
                        if (index != -1) {
                            adapter.notifyArrayItemRangeChanged(index, 1)
                            Toast.makeText(requireContext(), 
                                if (newStatus) "❤️ Añadido a Favoritos" else "💔 Quitado de Favoritos", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    companion object {
        const val COLUMNS = 4
        private const val FETCH_NEXT_THRESHOLD = COLUMNS * 6
        const val DEFAULT_FETCH_COUNT = 50
        const val FETCH_PAGE_COUNT = DEFAULT_FETCH_COUNT
    }
    
    protected open val fetchCount: Int = DEFAULT_FETCH_COUNT
}
