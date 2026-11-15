package nl.giejay.android.tv.immich.favorite

import android.os.Bundle
import arrow.core.Either
import androidx.fragment.app.setFragmentResultListener
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.VerticalGridPresenter
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.cache.FavoriteCache
import nl.giejay.android.tv.immich.shared.prefs.ALL_ASSETS_SORTING
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.GRID_COLUMN_COUNT
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber
import nl.giejay.android.tv.immich.shared.cache.SwrCache
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesBrowseFragment : GenericAssetFragment() {
    override val gridTitle: String? = "Favorites"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Favorites"
        
        if (!usePhotoTiles) {
            // Asegurar grid 3x3 (o el número configurado)
            val cols = try {
                PreferenceManager.get(GRID_COLUMN_COUNT)
            } catch (e: Exception) { 3 }

            (gridPresenter as? VerticalGridPresenter)?.apply {
                numberOfColumns = cols
                shadowEnabled = true
            }
        }
        
        // CLAVE: Escuchar cambios de favoritos desde otras pantallas
        setFragmentResultListener("asset_favorite_changed") { _, bundle ->
            val assetId = bundle.getString("assetId")
            val isFavorite = bundle.getBoolean("isFavorite")
            
            if (assetId != null) {
                Timber.d("Favorites: Cambio detectado $assetId -> $isFavorite")
                
                if (!isFavorite) {
                    // Si se desmarcó como favorito, ELIMINAR de esta lista
                    removeAssetFromView(assetId)
                } else {
                    // Si se marcó como favorito, actualizar (o agregar si no existe)
                    updateCardInAdapter(assetId, isFavorite)
                    updateAssetInMemory(assetId, isFavorite)
                }
            }
        }
    }
    
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // "231 photos" + Slideshow before the date pill (googletv-11)
        setHeaderAction(nl.giejay.android.tv.immich.shared.util.Tr.t("Slideshow"), android.R.drawable.ic_media_play, null) { startSlideshow() }
        val client = nl.giejay.android.tv.immich.shared.util.currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { client.assetStatistics(isFavorite = true) }
            stats.getOrNull()?.let { st ->
                setHeaderAction(nl.giejay.android.tv.immich.shared.util.Tr.t("Slideshow"), android.R.drawable.ic_media_play,
                    nl.giejay.android.tv.immich.shared.util.Tr.photos(st.total)) { startSlideshow() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // OPCIONAL: Si quieres refrescar completamente al volver
        // (útil si el usuario marcó muchos favoritos en otra pantalla)
        checkForRemovedFavorites()
    }
    
    /**
     * Elimina un asset de la vista cuando ya no es favorito
     */
    private fun removeAssetFromView(assetId: String) {
        if (adapter == null || adapter.size() == 0) return
        
        // Buscar y eliminar del adaptador
        for (i in 0 until adapter.size()) {
            val item = adapter.get(i)
            if (item is Card && item.id == assetId) {
                (adapter as? ArrayObjectAdapter)?.removeItems(i, 1)
                Timber.d("Favorites: Asset $assetId eliminado de la vista")
                
                // También eliminar de memoria
                if (assets is MutableList) {
                    val index = assets.indexOfFirst { it.id == assetId }
                    if (index != -1) {
                        (assets as MutableList).removeAt(index)
                    }
                }
                return
            }
        }
    }
    
    /**
     * Verifica si hay assets en pantalla que ya no son favoritos
     * (útil cuando vuelves de otra pantalla donde desmarcaste varios)
     */
    private fun checkForRemovedFavorites() {
        if (assets.isEmpty() || adapter.size() == 0) return
        
        val toRemove = mutableListOf<String>()
        
        // Buscar assets que según el cache ya no son favoritos
        for (asset in assets) {
            val cachedValue = FavoriteCache.overrides[asset.id]
            if (cachedValue == false) {
                toRemove.add(asset.id)
            }
        }
        
        // Eliminar todos los que ya no son favoritos
        toRemove.forEach { assetId ->
            removeAssetFromView(assetId)
        }
        
        if (toRemove.isNotEmpty()) {
            Timber.d("Favorites: Eliminados ${toRemove.size} assets que ya no son favoritos")
        }
    }

    /** True while the first page is being re-read from the server after the cached one was shown. */
    private var revalidated = false

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {
        val key = "favorites_p1_${currentFilter}"
        if (page == 1 && !revalidated) {
            // cached first page at once, the server is asked behind the scenes
            val cached = SwrCache.readList<Asset>(key)?.first
            if (cached != null && cached.isNotEmpty()) {
                ioScope.launch {
                    apiClient.listFavoriteAssets(1, 100, currentFilter).getOrNull()?.let { fresh ->
                        if (fresh.map { it.id } != cached.map { it.id }) {
                            SwrCache.writeList(key, fresh)
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    revalidated = true
                                    clearState()
                                    allPagesLoaded = false
                                    fetchInitialItems()
                                }
                            }
                        }
                    }
                }
                return Either.Right(cached)
            }
        }
        revalidated = false
        val result = apiClient.listFavoriteAssets(page, 100, currentFilter)
        if (page == 1) result.getOrNull()?.let { SwrCache.writeList(key, it) }
        return result
    }
    override fun sortItems(items: List<Asset>): List<Asset> {
        return items.sortedWith(currentSort.sort)
    }

    override fun getSortingKey() = ALL_ASSETS_SORTING
    override fun getFilterKey() = FILTER_CONTENT_TYPE
}
