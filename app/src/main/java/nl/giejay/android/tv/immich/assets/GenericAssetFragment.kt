package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import androidx.fragment.app.setFragmentResultListener
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.VerticalGridPresenter
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.shared.prefs.*
import nl.giejay.android.tv.immich.shared.util.toCard
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.util.LoadMore
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import timber.log.Timber

abstract class GenericAssetFragment : VerticalCardGridFragment<Asset>() {
    protected lateinit var currentFilter: ContentType
    protected lateinit var currentSort: PhotosOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        val sortingKey = getSortingKey()
        val filterKey = getFilterKey()
        currentSort = PreferenceManager.get(sortingKey)
        currentFilter = PreferenceManager.get(filterKey)
        
        super.onCreate(savedInstanceState)
        
        val cols = try {
            PreferenceManager.get(GRID_COLUMN_COUNT)
        } catch (e: Exception) { 4 }
        
        (gridPresenter as? VerticalGridPresenter)?.numberOfColumns = cols
        
        PreferenceManager.subscribeMultiple(listOf(sortingKey, filterKey, GRID_COLUMN_COUNT)) { state ->
            if(state[sortingKey.key()] != currentSort || state[filterKey.key()] != currentFilter){
                clearState()
                currentSort = state[sortingKey.key()] as PhotosOrder
                currentFilter = state[filterKey.key()] as ContentType
                fetchInitialItems()
            }
        }

        // Listener para actualizaciones inmediatas (cuando el fragmento sobrevive)
        setFragmentResultListener("asset_favorite_changed") { _, bundle ->
            val assetId = bundle.getString("assetId")
            val isFavorite = bundle.getBoolean("isFavorite")
            
            if (assetId != null) {
                Timber.d("GenericAsset: Evento recibido $assetId -> $isFavorite")
                
                // 1. Guardar en cache global (persiste entre destrucciones)
                FavoriteCache.overrides[assetId] = isFavorite
                
                // 2. Actualizar vista inmediatamente si está disponible
                updateCardInAdapter(assetId, isFavorite)
                
                // 3. Actualizar memoria (lista de assets)
                updateAssetInMemory(assetId, isFavorite)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Aplicar cache global al volver (crucial cuando el fragmento se recrea)
        Timber.d("GenericAsset: onResume - Aplicando cache global")
        applyGlobalCacheToAdapter()
    }
    
    /**
     * Actualiza un Card en el adaptador visual
     */
    private fun updateCardInAdapter(assetId: String, isFavorite: Boolean) {
        if (adapter == null || adapter.size() == 0) {
            Timber.d("GenericAsset: Adaptador no disponible para actualizar")
            return
        }
        
        val count = adapter.size()
        for (i in 0 until count) {
            val item = adapter.get(i)
            if (item is Card && item.id == assetId) {
                item.isFavorite = isFavorite
                (adapter as? ArrayObjectAdapter)?.notifyArrayItemRangeChanged(i, 1)
                Timber.d("GenericAsset: Card actualizado en adaptador en posición $i")
                return
            }
        }
        
        Timber.d("GenericAsset: Card $assetId no encontrado en adaptador")
    }
    
    /**
     * Actualiza un Asset en la memoria (lista assets)
     */
    private fun updateAssetInMemory(assetId: String, isFavorite: Boolean) {
        val index = assets.indexOfFirst { it.id == assetId }
        if (index != -1 && assets is MutableList) {
            val updatedAsset = assets[index].copy(isFavorite = isFavorite)
            (assets as MutableList)[index] = updatedAsset
            Timber.d("GenericAsset: Asset actualizado en memoria en índice $index")
        }
    }

    /**
     * Aplica el cache global de favoritos al adaptador
     * Se ejecuta en onResume para capturar cambios hechos en otras pantallas
     */
    private fun applyGlobalCacheToAdapter() {
        if (FavoriteCache.overrides.isEmpty()) {
            Timber.d("GenericAsset: Cache global vacío")
            return
        }
        
        if (adapter == null || adapter.size() == 0) {
            Timber.d("GenericAsset: Adaptador no disponible")
            return
        }
        
        var updatedCount = 0
        val count = adapter.size()
        
        for (i in 0 until count) {
            val item = adapter.get(i) ?: continue
            
            if (item is Card) {
                val cachedValue = FavoriteCache.overrides[item.id]
                if (cachedValue != null && item.isFavorite != cachedValue) {
                    item.isFavorite = cachedValue
                    (adapter as? ArrayObjectAdapter)?.notifyArrayItemRangeChanged(i, 1)
                    updatedCount++
                }
            }
        }
        
        if (updatedCount > 0) {
            Timber.d("GenericAsset: Aplicados $updatedCount cambios del cache global")
        }
        
        // También actualizar memoria
        applyGlobalCacheToMemory()
    }
    
    /**
     * Aplica el cache global a la lista de assets en memoria
     */
    private fun applyGlobalCacheToMemory() {
        if (assets.isEmpty() || FavoriteCache.overrides.isEmpty()) return
        
        if (assets is MutableList) {
            var updatedCount = 0
            for (i in assets.indices) {
                val asset = assets[i]
                val cachedValue = FavoriteCache.overrides[asset.id]
                if (cachedValue != null && asset.isFavorite != cachedValue) {
                    (assets as MutableList)[i] = asset.copy(isFavorite = cachedValue)
                    updatedCount++
                }
            }
            if (updatedCount > 0) {
                Timber.d("GenericAsset: Aplicados $updatedCount cambios a memoria")
            }
        }
    }

    override fun filterItems(items: List<Asset>): List<Asset> {
        return items.filter { currentFilter ==  ContentType.ALL || it.type.lowercase() == currentFilter.toString().lowercase() }
    }

    open fun getSortingKey(): EnumByTitlePref<PhotosOrder>{
        return ALL_ASSETS_SORTING
    }

    open fun getFilterKey(): EnumByTitlePref<ContentType>{
        return FILTER_CONTENT_TYPE
    }

    override fun sortItems(items: List<Asset>): List<Asset> {
        return items.sortedWith(currentSort.sort)
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no use case yet
    }

    open fun showMediaCount(): Boolean {
        return false
    }

    override fun openPopUpMenu() {
        findNavController().navigate(
            HomeFragmentDirections.actionGlobalToSettingsDialog("generic_asset_settings")
        )
    }

    override fun onItemClicked(card: Card) {
        val toSliderItems = assets.toSliderItems(keepOrder = true, mergePortrait = false)
        val loadMore: LoadMore = suspend {
            val moreAssets = loadMoreAssets()
            // also load the data in the overview
            setDataOnMain(moreAssets)
            moreAssets.toSliderItems(true, false)
        }

        findNavController().navigate(
            AlbumDetailsFragmentDirections.actionToPhotoSlider(
                MediaSliderConfiguration(
                    toSliderItems.indexOfFirst { it.ids().contains(card.id) },
                    PreferenceManager.get(SLIDER_INTERVAL),
                    PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                    isVideoSoundEnable = true,
                    toSliderItems,
                    loadMore,
                    { item -> manualUpdatePosition(this.assets.indexOfFirst { item.ids().contains(it.id) }) },
                    animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                    maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                    maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                    transformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                    debugEnabled = PreferenceManager.get(DEBUG_MODE),
                    enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                    gradiantOverlay = false,
                    metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
                )
            )
        )
    }

    override fun getBackgroundPicture(it: Asset): String? {
        val showBackground = PreferenceManager.get(nl.giejay.android.tv.immich.shared.prefs.LOAD_BACKGROUND_IMAGE)
        if (!showBackground) return null
        return ApiUtil.getFileUrl(it.id, "IMAGE")
    }

    override fun createCard(a: Asset): Card {
        return a.toCard()
    }
}
