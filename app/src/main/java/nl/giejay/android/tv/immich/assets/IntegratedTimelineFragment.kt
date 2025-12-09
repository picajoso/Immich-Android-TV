package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.assets.FavoriteCache

class IntegratedTimelineFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)
    
    private val viewModel: TimelineViewModel by viewModels()
    private lateinit var apiClient: ApiClient

    private lateinit var sidebarAdapter: ArrayObjectAdapter
    private lateinit var assetAdapter: ArrayObjectAdapter
    private lateinit var simpleAssetPresenter: SimpleAssetPresenter

    private var sidebarItems: List<AssetsSidebarItem> = emptyList()
    private var currentAssetList: List<Asset> = emptyList()

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
        
        titleSelection = view.findViewById(R.id.lbl_current_selection)
        assetCount = view.findViewById(R.id.lbl_asset_count)
        loader = view.findViewById(R.id.loading_indicator)
        sidebarRecyclerView = view.findViewById(R.id.sidebar_navigation_recycler_view)
        gridAssets = view.findViewById(R.id.grid_assets)

        setupApiClient()
        setupGrids()
        
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
        
        val newItems = mutableListOf<AssetsSidebarItem>()
        for (year in sortedYears) {
            newItems.add(AssetsSidebarItem.YearItem(year))
            bucketsByYear[year]?.let { months ->
                months.forEach { newItems.add(AssetsSidebarItem.MonthItem(it)) }
            }
        }
        
        sidebarItems = newItems
        sidebarAdapter.clear()
        sidebarAdapter.addAll(0, sidebarItems)
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
        val syncedAssets = currentAssetList.map { asset ->
            if (FavoriteCache.overrides.containsKey(asset.id)) {
                asset.copy(isFavorite = FavoriteCache.overrides[asset.id]!!)
            } else {
                asset
            }
        }
        val sliderItems = syncedAssets.toSliderItems(keepOrder = true, mergePortrait = false)
        val sliderStartIndex = sliderItems.indexOfFirst { it.ids().contains(card.id) }.let { if (it == -1) 0 else it }

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
            PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
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
                Toast.makeText(requireContext(), "Error navegando al visor", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- PRESENTERS ---

    inner class SidebarYearPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_card_new, parent, false)
            view.isFocusable = false
            return ViewHolder(view)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val yearItem = item as AssetsSidebarItem.YearItem
            viewHolder.view.findViewById<TextView>(R.id.year_title)?.text = yearItem.year
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    inner class SidebarMonthPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_month, parent, false)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            return ViewHolder(view)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val monthItem = item as AssetsSidebarItem.MonthItem
            val view = viewHolder.view

            view.setOnClickListener { 
                viewModel.selectBucket(monthItem.bucket.timeBucket, apiClient)
            }

            view.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val grid = v.rootView.findViewById<VerticalGridView>(R.id.grid_assets)
                    if (assetAdapter.size() > 0) {
                        grid.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }

            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    viewModel.selectBucket(monthItem.bucket.timeBucket, apiClient)
                }
                // Actualizamos visualmente usando la referencia local, sin esperar al Adapter
                updateVisualState(view, true, hasFocus, monthItem.bucket)
            }
            
            // Bind inicial
            updateVisualState(view, monthItem.isSelected, view.hasFocus(), monthItem.bucket)
        }
        
        private fun updateVisualState(view: View, isSelected: Boolean, hasFocus: Boolean, bucket: Bucket) {
            val monthNameView = view.findViewById<TextView>(R.id.month_name)
            val countView = view.findViewById<TextView>(R.id.month_count)
            val context = view.context

            val monthName = try {
                val date = LocalDate.parse(bucket.timeBucket)
                date.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
            } catch (e: Exception) { bucket.timeBucket }

            monthName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }?.let {
                monthNameView.text = it
            }
            countView.text = "${bucket.count}"

            if (isSelected || hasFocus) {
                view.setBackgroundColor(context.getColor(R.color.selected_month_background))
                monthNameView.setTextColor(context.getColor(R.color.selected_text_color))
                countView.setTextColor(context.getColor(R.color.selected_text_color))
            } else {
                view.setBackgroundColor(context.getColor(android.R.color.transparent))
                monthNameView.setTextColor(context.getColor(R.color.unselected_text_color))
                countView.setTextColor(context.getColor(R.color.unselected_text_color))
            }
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            viewHolder.view.setOnClickListener(null)
            viewHolder.view.onFocusChangeListener = null
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

            // 3. Listener de Click
            view.setOnClickListener {
                openPhotoSlider(card)
            }

            // 4. ANIMACIÓN DE FOCO (La clave para que se vea)
            view.setOnFocusChangeListener { v, hasFocus ->
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
