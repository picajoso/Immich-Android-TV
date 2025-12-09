package nl.giejay.android.tv.immich.slider

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.assets.FavoriteCache
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.view.MediaSliderFragment
import nl.giejay.mediaslider.view.MediaSliderView
import timber.log.Timber

class ImmichMediaSlider : MediaSliderFragment() {

    private lateinit var apiClient: ApiClient
    private lateinit var config: MediaSliderConfiguration

    @SuppressLint("UnsafeOptInUsageError")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        this.onAssetFavoriteChanged = { itemHolder, isFavorite ->
            if (view is MediaSliderView) {
                val item = itemHolder.mainItem
                val id = itemHolder.ids().firstOrNull()
                
                if (id != null) {
                    performToggleFavorite(id, item, view)
                }
            }
        }

        Timber.i("Loading ${this.javaClass.simpleName}")

        val bundle = ImmichMediaSliderArgs.fromBundle(requireArguments())
        this.config = bundle.config

        if (config.items.isEmpty()) {
            Toast.makeText(requireContext(), "No items to play", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        setupApiClient()

        setDefaultExoFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("x-api-key" to PreferenceManager.get(API_KEY)))
        )

        loadMediaSliderView(config)

        setupKeyInterceptor(view)
    }

    override fun onResume() {
        super.onResume()
        
        // Aplicar cache global a los items del slider
        Timber.d("VISOR: onResume - Aplicando cache a ${config.items.size} items")
        var updatedCount = 0
        
        config.items.forEach { itemHolder ->
            val ids = itemHolder.ids()
            ids.forEach { assetId ->
                val cachedValue = FavoriteCache.overrides[assetId]
                if (cachedValue != null && itemHolder.mainItem.isFavorite != cachedValue) {
                    itemHolder.mainItem.isFavorite = cachedValue
                    updatedCount++
                    Timber.d("VISOR: Item $assetId actualizado desde cache -> $cachedValue")
                }
            }
        }
        
        if (updatedCount > 0) {
            Timber.d("VISOR: Aplicados $updatedCount cambios del cache")
            
            // Forzar actualización visual usando el método correcto
            view?.let { currentView ->
                if (currentView is MediaSliderView) {
                    // Post para asegurar que la vista está lista
                    currentView.post {
                        currentView.updateFavoriteIcon()
                        Timber.d("VISOR: Icono de favorito refrescado")
                    }
                }
            }
        }
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

    private fun setupKeyInterceptor(view: View) {
        if (view is MediaSliderView) {
            view.onLongPressCenterListener = {
                toggleFavoriteCurrentItem(view)
            }
        }
    }

    private fun toggleFavoriteCurrentItem(view: MediaSliderView) {
        val currentPosition = view.getCurrentIndex()
        
        if (currentPosition in config.items.indices) {
            val currentItemHolder = config.items[currentPosition]
            val assetId = currentItemHolder.ids().firstOrNull()
            
            if (assetId != null) {
                val item = currentItemHolder.mainItem 
                performToggleFavorite(assetId, item, view)
            }
        }
    }

    private fun performToggleFavorite(assetId: String, sliderItem: nl.giejay.mediaslider.model.SliderItem, view: MediaSliderView) {
        val newStatus = !sliderItem.isFavorite

        lifecycleScope.launch(Dispatchers.IO) {
            apiClient.toggleFavorite(assetId, newStatus).fold(
                { error -> 
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                },
                { _ ->
                    withContext(Dispatchers.Main) {
                        sliderItem.isFavorite = newStatus
                        view.updateFavoriteIcon()
                        
                        val msg = if (newStatus) "❤️ Favorito" else "💔 No Favorito"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

                        FavoriteCache.overrides[assetId] = newStatus
                        Timber.d("VISOR: Guardado en FavoriteCache: $assetId -> $newStatus")
                        Timber.d("VISOR: Cache ahora tiene ${FavoriteCache.overrides.size} elementos")
                        
                        setFragmentResult("asset_favorite_changed", bundleOf(
                            "assetId" to assetId,
                            "isFavorite" to newStatus
                        ))
                        
                        Timber.d("VISOR: Evento enviado")
                    }
                }
            )
        }
    }
}
