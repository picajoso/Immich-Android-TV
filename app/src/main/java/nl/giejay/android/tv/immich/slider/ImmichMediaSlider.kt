package nl.giejay.android.tv.immich.slider

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.viewmodel.KeyEventsViewModel
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.view.MediaSliderFragment
import nl.giejay.mediaslider.view.MediaSliderView
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import nl.giejay.android.tv.immich.shared.cache.FavoriteCache
import timber.log.Timber

class ImmichMediaSlider : MediaSliderFragment() {

    private lateinit var apiClient: ApiClient
    private lateinit var config: MediaSliderConfiguration
    private lateinit var keyEvents: KeyEventsViewModel

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

        keyEvents = ViewModelProvider(requireActivity())[KeyEventsViewModel::class.java]

        if (config.items.isEmpty()) {
            Toast.makeText(requireContext(), nl.giejay.android.tv.immich.shared.util.Tr.t("No items to play"), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        setupApiClient()

        // Listen for delete button (PROG_RED) normal press events
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                keyEvents.deleteEventTrigger.collect { deleteTrigger ->
                    // Move current item to trash directly when delete button is pressed
                    if (deleteTrigger > 0) {
                        deleteCurrentItem()
                    }
                }
            }
        }

        setDefaultExoFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("x-api-key" to PreferenceManager.get(API_KEY)))
        )

        // photo only + info pill / info panel, styled by the active interface style
        if (view is MediaSliderView) {
            view.enableViewerOverlay(UiStyleProvider.viewerStyle())
            view.onToggleFavoriteRequested = { toggleFavoriteCurrentItem(view) }
        }

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

    private fun deleteCurrentItem() {
        Timber.d("VISOR: deleteCurrentItem() called")
        val view = this@ImmichMediaSlider.view
        if (view !is MediaSliderView) {
            Timber.e("VISOR: View is not MediaSliderView!")
            return
        }

        val currentPosition = view.getCurrentIndex()
        Timber.d("VISOR: Current position: $currentPosition, total items: ${config.items.size}")

        if (currentPosition in config.items.indices) {
            val currentItemHolder = config.items[currentPosition]
            val assetId = currentItemHolder.ids().firstOrNull()
            Timber.d("VISOR: Asset ID: $assetId")

            if (assetId != null) {
                moveToTrash(assetId, currentPosition)
            } else {
                Timber.e("VISOR: Asset ID is null!")
            }
        } else {
            Timber.e("VISOR: Current position not in config.items.indices!")
        }
    }

    private fun moveToTrash(assetId: String, position: Int) {
        Toast.makeText(requireContext(), "🗑️ Enviada a la papelera", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            apiClient.moveToTrash(assetId).fold(
                { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                },
                { _ ->
                    withContext(Dispatchers.Main) {
                        // Remove from config items by creating a new list without deleted item
                        config.items = config.items.filterIndexed { index, _ -> index != position }

                        // If there are still items, reload slider
                        if (config.items.isNotEmpty()) {
                            view?.let { currentView ->
                                if (currentView is MediaSliderView) {
                                    currentView.post {
                                        currentView.setItems(config.items)
                                    }
                                }
                            }
                        } else {
                            // No more items, go back
                            findNavController().popBackStack()
                        }

                        // Send fragment result to update other views
                        setFragmentResult("asset_deleted", bundleOf("assetId" to assetId))
                    }
                }
            )
        }
    }
}
