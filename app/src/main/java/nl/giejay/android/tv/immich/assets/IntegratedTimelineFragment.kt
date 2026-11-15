package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels 
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.VerticalGridView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.*
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.model.SliderItemViewHolder
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.cache.FavoriteCache

class IntegratedTimelineFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)
    
    private val viewModel: TimelineViewModel by viewModels()
    private lateinit var apiClient: ApiClient

    private lateinit var sidebarAdapter: ArrayObjectAdapter
    private lateinit var assetAdapter: ArrayObjectAdapter
    private lateinit var simpleAssetPresenter: SimpleAssetPresenter

    private var sidebarItems: List<AssetsSidebarItem> = emptyList()
    private var currentAssetList: List<Asset> = emptyList()
    private var returningFromGrid = false

    private lateinit var titleSelection: TextView
    private lateinit var assetCount: TextView
    private lateinit var loader: ProgressBar
    private lateinit var sidebarRecyclerView: RecyclerView
    private lateinit var gridAssets: VerticalGridView

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<IntegratedTimelineFragment> {
        return mMainFragmentAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_timeline_integrated, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        Timber.tag("CRITICAL").e("EXECUTION CONFIRMED: IntegratedTimelineFragment Loaded")
        Toast.makeText(requireContext(), "DEBUG: Timeline Loaded", Toast.LENGTH_SHORT).show()
        
        titleSelection = view.findViewById(R.id.lbl_current_selection)
        assetCount = view.findViewById(R.id.lbl_asset_count)
        loader = view.findViewById(R.id.loading_indicator)
        sidebarRecyclerView = view.findViewById(R.id.sidebar_navigation_recycler_view)
        gridAssets = view.findViewById(R.id.grid_assets)

        setupApiClient()
        setupGrids()
        
        setupGrids()
        
        // Enforce text-only mode
        titleSelection.visibility = View.GONE
        assetCount.visibility = View.GONE
        gridAssets.visibility = View.GONE
        
        // Rejilla oculta, sidebar ocupa todo el ancho
        val gridParams = view.findViewById<View>(R.id.grid_container).layoutParams as LinearLayout.LayoutParams
        gridParams.width = 0
        gridParams.weight = 0f
        view.findViewById<View>(R.id.grid_container).layoutParams = gridParams
        
        val params = sidebarRecyclerView.layoutParams as LinearLayout.LayoutParams
        params.width = LinearLayout.LayoutParams.MATCH_PARENT
        params.weight = 1f
        sidebarRecyclerView.layoutParams = params

        viewModel.loadBuckets(apiClient)
        observeViewModel()
    }

    private fun setupApiClient() {
        apiClient = ApiClient.getClient(
            ApiClientConfig(
                PreferenceManager.get(HOST_NAME),
                PreferenceManager.get(API_KEY),
                PreferenceManager.get(DISABLE_SSL_VERIFICATION),
                PreferenceManager.get(DEBUG_MODE)
            )
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                
                launch {
                    viewModel.buckets.collect { buckets ->
                        if (buckets.isNotEmpty()) {
                            updateSidebar(buckets)
                            
                            // Si acabamos de volver y tenemos una selección, restauramos el foco UNA VEZ
                            if (returningFromGrid) {
                                val selectedId = viewModel.selectedBucketId.value
                                if (selectedId != null) {
                                    scrollToAndFocusBucket(selectedId)
                                }
                                returningFromGrid = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.selectedBucketId.collect { selectedId ->
                        if (selectedId != null) {
                            updateSidebarSelection(selectedId)
                            val bucket = viewModel.getSelectedBucket()
                            bucket?.let { updateTitle(it) }
                        }
                    }
                }

                launch {
                    viewModel.assets.collect { assets ->
                        currentAssetList = assets
                        updateGrid(assets)
                    }
                }
                
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        loader.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateSidebar(buckets: List<Bucket>) {
        val bucketsByYear = buckets.groupBy { 
            try { it.timeBucket.substring(0, 4) } catch(e: Exception) { "Unknown" } 
        }
        val sortedYears = bucketsByYear.keys.toList().sortedDescending()
        
        val selectedId = viewModel.selectedBucketId.value
        val selectedYear = try { selectedId?.substring(0, 4) } catch(e: Exception) { null }
        val newItems = mutableListOf<AssetsSidebarItem>()

        for (year in sortedYears) {
            val existingYearItem = sidebarItems.find { it is AssetsSidebarItem.YearItem && it.year == year } as? AssetsSidebarItem.YearItem
            
            // Si hay una selección activa (venimos de volver atrás o carga inicial), 
            // forzamos la expansión de ese año y el colapso de los demás.
            var isExpanded = if (selectedYear != null) {
                year == selectedYear
            } else {
                existingYearItem?.isExpanded ?: false
            }

            newItems.add(AssetsSidebarItem.YearItem(year, isExpanded))
            if (isExpanded) {
                bucketsByYear[year]?.let { months ->
                    months.forEach { 
                        val isItemSelected = it.timeBucket == selectedId
                        newItems.add(AssetsSidebarItem.MonthItem(it, isItemSelected)) 
                    }
                }
            }
        }
        
        sidebarItems = newItems
        sidebarAdapter.clear()
        sidebarAdapter.addAll(0, sidebarItems)
    }

    private fun updateSidebarWithExpansion(year: String, expanded: Boolean) {
        val newItems = mutableListOf<AssetsSidebarItem>()
        val buckets = viewModel.buckets.value
        val bucketsByYear = buckets.groupBy { 
            try { it.timeBucket.substring(0, 4) } catch(e: Exception) { "Unknown" } 
        }
        val sortedYears = bucketsByYear.keys.toList().sortedDescending()

        for (y in sortedYears) {
            // Solo permitimos un año expandido a la vez
            val isThisYearExpanded = if (y == year) expanded else false
            
            newItems.add(AssetsSidebarItem.YearItem(y, isThisYearExpanded))
            if (isThisYearExpanded) {
                bucketsByYear[y]?.let { months ->
                    months.forEach { newItems.add(AssetsSidebarItem.MonthItem(it)) }
                }
            }
        }

        sidebarItems = newItems
        sidebarAdapter.clear()
        sidebarAdapter.addAll(0, sidebarItems)

        // Restaurar el foco en el año clicado y desplazarlo arriba
        val newIndex = sidebarItems.indexOfFirst { it is AssetsSidebarItem.YearItem && it.year == year }
        if (newIndex != -1) {
            val itemToFocus = sidebarItems[newIndex]
            sidebarRecyclerView.post {
                // Desplazar el año arriba del todo
                (sidebarRecyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(newIndex, 0)
                
                sidebarRecyclerView.postDelayed({
                    val viewHolder = sidebarRecyclerView.findViewHolderForAdapterPosition(newIndex)
                    val container = viewHolder?.itemView?.findViewById<View>(R.id.year_container)
                    container?.requestFocus()
                    
                    // Forzar actualización visual del resaltado
                    if (viewHolder != null) {
                        val presenter = sidebarAdapter.getPresenter(itemToFocus) as? SidebarYearPresenter
                        presenter?.onBindViewHolder(Presenter.ViewHolder(viewHolder.itemView), itemToFocus)
                    }
                }, 100)
            }
        }
    }

    private fun scrollToAndFocusBucket(selectedId: String) {
        val index = sidebarItems.indexOfFirst { it is AssetsSidebarItem.MonthItem && it.bucket.timeBucket == selectedId }
        if (index != -1) {
            sidebarRecyclerView.post {
                (sidebarRecyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(index, 200)
                
                sidebarRecyclerView.postDelayed({
                    val vh = sidebarRecyclerView.findViewHolderForAdapterPosition(index)
                    val container = vh?.itemView?.findViewById<View>(R.id.month_container)
                    container?.requestFocus()
                }, 300)
            }
        } else {
            // Si el mes no está visible (ej: el año está plegado), enfocamos el año
            val year = try { selectedId.substring(0, 4) } catch(e: Exception) { null }
            val yearIndex = sidebarItems.indexOfFirst { it is AssetsSidebarItem.YearItem && it.year == year }
            if (yearIndex != -1) {
                sidebarRecyclerView.post {
                    sidebarRecyclerView.scrollToPosition(yearIndex)
                    sidebarRecyclerView.postDelayed({
                        val vh = sidebarRecyclerView.findViewHolderForAdapterPosition(yearIndex)
                        vh?.itemView?.findViewById<View>(R.id.year_container)?.requestFocus()
                    }, 300)
                }
            }
        }
    }

    private fun updateSidebarSelection(selectedId: String) {
        if (sidebarItems.isEmpty()) return

        val newItems = sidebarItems.map { item ->
            if (item is AssetsSidebarItem.MonthItem) {
                item.copy(isSelected = item.bucket.timeBucket == selectedId)
            } else {
                item
            }
        }

        for (i in sidebarItems.indices) {
            val oldItem = sidebarItems[i]
            val newItem = newItems[i]
            if (oldItem != newItem) {
                sidebarAdapter.replace(i, newItem)
            }
        }
        sidebarItems = newItems

        val index = sidebarItems.indexOfFirst { it is AssetsSidebarItem.MonthItem && it.bucket.timeBucket == selectedId }
        if (index != -1) {
           // Scroll suave opcional si se queda fuera de pantalla
        }
    }

    private fun updateTitle(bucket: Bucket) {
        val prettyDate = try {
            val date = LocalDate.parse(bucket.timeBucket)
            date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        } catch (e: Exception) { bucket.timeBucket }

        titleSelection.text = prettyDate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        assetCount.text = "(${bucket.count} items)"
    }

    private fun updateGrid(assets: List<Asset>) {
        val host = PreferenceManager.get(HOST_NAME).trimEnd('/')
        val apiKey = PreferenceManager.get(API_KEY)
        
        val cards = assets.map { asset ->
            val isFav = if (FavoriteCache.overrides.containsKey(asset.id)) {
                FavoriteCache.overrides[asset.id]!!
            } else {
                asset.isFavorite
            }
            val fullUrl = "$host/api/assets/${asset.id}/thumbnail?x-api-key=$apiKey"
            Card(
                id = asset.id,
                title = "",
                description = "",
                thumbnailUrl = fullUrl,
                backgroundUrl = fullUrl,
                isFavorite = isFav
            )
        }
        
        gridAssets.scrollToPosition(0)
        assetAdapter.clear()
        assetAdapter.addAll(0, cards)
    }

    private fun setupGrids() {
        // --- PANEL LATERAL ---
        val sidebarPresenterSelector = object : PresenterSelector() {
            private val yearPresenter = SidebarYearPresenter()
            private val monthPresenter = SidebarMonthPresenter()
            override fun getPresenter(item: Any?): Presenter? {
                return when (item) {
                    is AssetsSidebarItem.YearItem -> yearPresenter
                    is AssetsSidebarItem.MonthItem -> monthPresenter
                    else -> null
                }
            }
        }

        sidebarAdapter = ArrayObjectAdapter(sidebarPresenterSelector)
        sidebarRecyclerView.adapter = ItemBridgeAdapter(sidebarAdapter)
        sidebarRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        // Navegación Sidebar -> Grid
        sidebarRecyclerView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (assetAdapter.size() > 0) {
                    gridAssets.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        // --- PANEL DERECHO (ASSETS) ---
        simpleAssetPresenter = SimpleAssetPresenter()
        assetAdapter = ArrayObjectAdapter(simpleAssetPresenter)

        // CORRECCIÓN: Cambiado a 3 columnas para parecerse a Google Photos TV
        val NUM_COLUMNS = 3 
        gridAssets.setNumColumns(NUM_COLUMNS)
        gridAssets.setItemSpacing(16)

        val assetBridgeAdapter = ItemBridgeAdapter(assetAdapter)
        gridAssets.adapter = assetBridgeAdapter
        gridAssets.isFocusable = true
        gridAssets.isFocusableInTouchMode = false

        // Navegación Grid -> Sidebar
        gridAssets.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val position = gridAssets.selectedPosition
                if (position != -1 && position % NUM_COLUMNS == 0) {
                    val currentSelectedId = viewModel.selectedBucketId.value
                    val index = sidebarItems.indexOfFirst { 
                        it is AssetsSidebarItem.MonthItem && it.bucket.timeBucket == currentSelectedId 
                    }
                    
                    if (index != -1) {
                        sidebarRecyclerView.scrollToPosition(index)
                        sidebarRecyclerView.post {
                            val vh = sidebarRecyclerView.findViewHolderForAdapterPosition(index)
                            if (vh != null) vh.itemView.requestFocus() else sidebarRecyclerView.requestFocus()
                        }
                    } else {
                        sidebarRecyclerView.requestFocus()
                    }
                    return@setOnKeyListener true
                }
            }
            false
        }

        assetBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.isClickable = true
                viewHolder.itemView.isFocusable = true
                viewHolder.itemView.isFocusableInTouchMode = true
                
                viewHolder.itemView.setOnClickListener {
                    val position = viewHolder.bindingAdapterPosition
                    if (position != -1 && position < assetAdapter.size()) {
                        val card = assetAdapter.get(position) as? Card
                        card?.let { openPhotoSlider(it) }
                    }
                }

                // CORRECCIÓN: Eliminada la animación de escala manual. 
                // El CardView ya maneja la elevación y el foco de forma nativa.
                /*
                viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.1f).scaleY(1.1f).duration = 150
                        v.elevation = 10f
                        v.isSelected = true
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).duration = 150
                        v.elevation = 0f
                        v.isSelected = false
                    }
                }
                */
            }
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {}
        })
    }

    private fun openPhotoSlider(card: Card) {
        Timber.tag("CRITICAL").e("EXECUTION CONFIRMED: openPhotoSlider Called for card ${card.id}")
        val syncedAssets = currentAssetList.map { asset ->
            if (FavoriteCache.overrides.containsKey(asset.id)) {
                asset.copy(isFavorite = FavoriteCache.overrides[asset.id]!!)
            } else {
                asset
            }
        }
        val sliderItems: List<SliderItemViewHolder> = syncedAssets.toSliderItems(keepOrder = true, mergePortrait = false)
        val sliderStartIndex = sliderItems.indexOfFirst { 
            it.mainItem.id == card.id || it.secondaryItem?.id == card.id 
        }.let { if (it == -1) 0 else it }

        var loadPreviousCallback: (suspend () -> List<SliderItemViewHolder>)? = null

        // IMPORTANTE: Definimos qué hacer al llegar al inicio (izquierda)
        val currentBucketId = card.id.let { assetId ->
            val bucketId = viewModel.selectedBucketId.value
            val debugMsg = "DEBUG: Bucket ${bucketId} | Is Current? unknown"
            Timber.tag("CRITICAL").e(debugMsg)
            bucketId
        }

        if (currentBucketId != null) {
            val buckets = viewModel.buckets.value
            val currentIndex = buckets.indexOfFirst { it.timeBucket == currentBucketId }
            
            val debugMsg = "DEBUG: Bucket ${currentBucketId} | Is Current? ${currentIndex==0} | Index $currentIndex of ${buckets.size}"
            Timber.tag("SLIDER").d(debugMsg)
            // UNCONDITIONAL TOAST for debugging
            Toast.makeText(requireContext(), debugMsg, Toast.LENGTH_LONG).show()

            Timber.tag("SLIDER").d("Full Bucket List: ${buckets.joinToString { it.timeBucket }}")

            // Si hay un bucket ANTES en la lista (index - 1), ese es el más reciente (Newer)
            if (currentIndex > 0) {
                 val newerBucket = buckets[currentIndex - 1]
                 Timber.tag("SLIDER").d("Target Newer Bucket: ${newerBucket.timeBucket} (Index ${currentIndex - 1})")
                 
                 loadPreviousCallback = suspend {
                     Timber.tag("SLIDER").d("Trigger loadPrevious for bucket ${newerBucket.timeBucket}")
                     // Cargamos los assets del nuevo bucket
                     val result = apiClient.getAssetsForBucket("", newerBucket.timeBucket, PhotosOrder.NEWEST_OLDEST)
                     result.fold(
                         { emptyList() }, // Error
                         { newAssets -> 
                             // Sincronizar favoritos
                             val synced = newAssets.map { asset ->
                                 if (FavoriteCache.overrides.containsKey(asset.id)) {
                                     asset.copy(isFavorite = FavoriteCache.overrides[asset.id]!!)
                                 } else asset
                             }
                             synced.toSliderItems(true, false)
                         }
                     )
                 }
            } else {
                Timber.tag("SLIDER").d("No newer bucket found. Current Index: $currentIndex")
                // DIAGNOSTIC TOAST
                if (currentIndex == 0) {
                     Toast.makeText(requireContext(), "DEBUG: Estás en el mes más reciente (Index 0)", Toast.LENGTH_LONG).show()
                } else {
                     Toast.makeText(requireContext(), "DEBUG: No se encontró mes anterior (Index $currentIndex)", Toast.LENGTH_LONG).show()
                }
            }
        } else {
             Timber.tag("SLIDER").d("CurrentBucketID is null")
             Toast.makeText(requireContext(), "DEBUG: No se pudo identificar el mes actual", Toast.LENGTH_LONG).show()
        }
        
        if (loadPreviousCallback != null) {
             Toast.makeText(requireContext(), nl.giejay.android.tv.immich.shared.util.Tr.t("Continuous navigation enabled"), Toast.LENGTH_SHORT).show()
        }

        val config = MediaSliderConfiguration(
            sliderStartIndex,
            PreferenceManager.get(SLIDER_INTERVAL),
            PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
            isVideoSoundEnable = true,
            sliderItems,
            loadMore = null,
            onAssetSelected = { },
            PreferenceManager.get(SLIDER_ANIMATION_SPEED),
            PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
            PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
            PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
            PreferenceManager.get(DEBUG_MODE),
            gradiantOverlay = false,
            PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
            PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER),
            loadPreviousCallback // Passed explicitly!
        )

        val bundle = Bundle()
        bundle.putParcelable("config", config)

        try {
            findNavController().navigate(R.id.action_homeFragment_to_photo_slider, bundle)
        } catch (e: Exception) {
            Timber.e(e, "Error opening photo slider via HomeFragment action")
            try {
                findNavController().navigate(R.id.action_timeline_to_photo_slider, bundle)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), nl.giejay.android.tv.immich.shared.util.Tr.t("Error navigating to the viewer"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- PRESENTERS ---

    inner class SidebarYearPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_year, parent, false)
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            return ViewHolder(view)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val yearItem = item as AssetsSidebarItem.YearItem
            val view = viewHolder.view
            val container = view.findViewById<View>(R.id.year_container)
            val titleView = view.findViewById<TextView>(R.id.year_title)
            val accentBar = view.findViewById<View>(R.id.accent_bar)
            val context = view.context
            
            titleView?.text = yearItem.year
            
            container?.setOnClickListener {
                updateSidebarWithExpansion(yearItem.year, !yearItem.isExpanded)
            }

            container?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    container.setBackgroundColor(context.getColor(R.color.selected_month_background))
                    titleView?.setTextColor(context.getColor(R.color.selected_text_color))
                    accentBar?.visibility = View.VISIBLE
                } else {
                    container.setBackgroundColor(context.getColor(android.R.color.transparent))
                    titleView?.setTextColor(context.getColor(android.R.color.white))
                    accentBar?.visibility = View.INVISIBLE
                }
            }
            
            // Estado inicial
            if (container?.hasFocus() == true) {
                container.setBackgroundColor(context.getColor(R.color.selected_month_background))
                titleView?.setTextColor(context.getColor(R.color.selected_text_color))
                accentBar?.visibility = View.VISIBLE
            } else {
                container?.setBackgroundColor(context.getColor(android.R.color.transparent))
                titleView?.setTextColor(context.getColor(android.R.color.white))
                accentBar?.visibility = View.INVISIBLE
            }
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val container = viewHolder.view.findViewById<View>(R.id.year_container)
            container?.setOnClickListener(null)
            container?.setOnFocusChangeListener(null)
        }
    }

    inner class SidebarMonthPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_month, parent, false)
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            return ViewHolder(view)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val monthItem = item as AssetsSidebarItem.MonthItem
            val view = viewHolder.view

            view.setOnClickListener(null)
            view.isFocusable = false
            view.isFocusableInTouchMode = false

            // Removed KeyListener that directed focus to grid when thumbnails were enabled
            view.setOnKeyListener(null)

            val container = view.findViewById<View>(R.id.month_container)
            
            container?.setOnClickListener {
                viewModel.selectBucket(monthItem.bucket.timeBucket, apiClient)
                val bundle = Bundle()
                bundle.putString("timeBucket", monthItem.bucket.timeBucket)
                returningFromGrid = true
                findNavController().navigate(R.id.action_global_to_photo_grid, bundle)
            }

            container?.setOnFocusChangeListener { _, hasFocus ->
                updateVisualState(view, monthItem.isSelected, hasFocus, monthItem.bucket)
            }
            
            // Bind inicial
            updateVisualState(view, monthItem.isSelected, container?.hasFocus() == true, monthItem.bucket)
        }
        
        private fun updateVisualState(view: View, isSelected: Boolean, hasFocus: Boolean, bucket: Bucket) {
            val container = view.findViewById<View>(R.id.month_container)
            val monthNameView = view.findViewById<TextView>(R.id.month_name)
            val countView = view.findViewById<TextView>(R.id.month_count)
            val accentBar = view.findViewById<View>(R.id.accent_bar)
            val context = view.context

            val isTextOnly = true
            val shouldHighlight = hasFocus // In text-only mode, highlight follows focus

            val monthName = try {
                val date = LocalDate.parse(bucket.timeBucket)
                date.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
            } catch (e: Exception) { bucket.timeBucket }

            monthName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }?.let {
                monthNameView.text = it
            }
            countView.text = "(${bucket.count})"

            if (shouldHighlight) {
                container?.setBackgroundColor(context.getColor(R.color.selected_month_background))
                monthNameView?.setTextColor(context.getColor(R.color.selected_text_color))
                countView?.setTextColor(context.getColor(R.color.selected_text_color))
                accentBar?.visibility = View.VISIBLE
            } else {
                container?.setBackgroundColor(context.getColor(android.R.color.transparent))
                monthNameView?.setTextColor(context.getColor(R.color.unselected_text_color))
                countView?.setTextColor(context.getColor(R.color.unselected_text_color))
                accentBar?.visibility = View.INVISIBLE
            }
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val container = viewHolder.view.findViewById<View>(R.id.month_container)
            container?.setOnClickListener(null)
            container?.onFocusChangeListener = null
            viewHolder.view.setOnKeyListener(null)
        }
    }

/**
     * Presenter simple para las fotos.
     */
    inner class SimpleAssetPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_card_new, parent, false)
            
            // IMPORTANTE: Permitir que los hijos dibujen fuera de los límites para la sombra/zoom
            (parent as? ViewGroup)?.clipChildren = false
            (parent as? ViewGroup)?.clipToPadding = false
            
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val card = item as Card
            val view = viewHolder.view
            val imageView = view.findViewById<ImageView>(R.id.image_view)
            
            // 1. Cargar imagen
            Glide.with(view.context)
                .load(card.thumbnailUrl)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imageView)

            // 2. Icono favorito
            val favIcon = view.findViewById<ImageView>(R.id.icon_favorite)
            favIcon.visibility = if (card.isFavorite) View.VISIBLE else View.GONE

            // 3. Listener de Click (Redundant backup, often ignored by Leanback/Grid)
            view.setOnClickListener {
                Timber.tag("CRITICAL").e("EXECUTION CONFIRMED: Click Listener Fired")
                openPhotoSlider(card)
            }

            // 4. KEY LISTENER (Force DPAD_CENTER / ENTER)
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            Timber.tag("CRITICAL").e("EXECUTION CONFIRMED: KeyListener DPAD_CENTER/ENTER Fired")
                            openPhotoSlider(card)
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            // 5. ANIMACIÓN DE FOCO (La clave para que se vea)
            view.setOnFocusChangeListener { v, hasFocus ->
                // ... (Existing animation logic)
                if (hasFocus) {
                    // Zoom In (Crecer)
                    v.animate().scaleX(1.1f).scaleY(1.1f).duration = 150
                    v.elevation = 10f
                    // Forzar estado 'selected' para que el drawable del borde se active si usa state_selected
                    v.isSelected = true 
                } else {
                    // Zoom Out (Volver a normal)
                    v.animate().scaleX(1.0f).scaleY(1.0f).duration = 150
                    v.elevation = 2f
                    v.isSelected = false
                }
            }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val imageView = viewHolder.view.findViewById<ImageView>(R.id.image_view)
            Glide.with(viewHolder.view.context).clear(imageView)
            // Limpiar listeners
            viewHolder.view.setOnClickListener(null)
            viewHolder.view.onFocusChangeListener = null
        }
    }

}
