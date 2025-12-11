package nl.giejay.android.tv.immich.onthisday

import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenter
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.util.toCard
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import timber.log.Timber
import java.util.Calendar

class OnThisDayBrowseFragment : RowsSupportFragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var apiClient: ApiClient
    private lateinit var rowsAdapter: ArrayObjectAdapter
    
    // Necesario para que funcione dentro de HomeFragment
    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return mMainFragmentAdapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        setupClient()
        loadOnThisDayAssets()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card = item as? Card ?: return@OnItemViewClickedListener
            onItemClicked(card)
        }
    }

    private fun setupClient() {
        apiClient = ApiClient.getClient(
            ApiClientConfig(
                PreferenceManager.get(HOST_NAME),
                PreferenceManager.get(API_KEY),
                PreferenceManager.get(DISABLE_SSL_VERIFICATION),
                PreferenceManager.get(DEBUG_MODE)
            )
        )
    }

    private fun loadOnThisDayAssets() {
        ioScope.launch {
            try {
                val result = apiClient.onThisDayAssets(
                    1,
                    1000,
                    PreferenceManager.get(FILTER_CONTENT_TYPE)
                )
                
                result.fold(
                    { error ->
                        Timber.e("Error loading 'On this day' assets: $error")
                    },
                    { assets ->
                        Timber.d("Loaded ${assets.size} assets for 'On this day'")
                        
                        val groupedAssets = assets.groupBy {
                            val cal = Calendar.getInstance()
                            it.exifInfo?.dateTimeOriginal?.let { date ->
                                cal.time = date
                                cal.get(Calendar.YEAR)
                            }
                        }
                        
                        requireActivity().runOnUiThread {
                            rowsAdapter.clear()
                            val cardPresenter = CardPresenter(requireContext())
                            
                            groupedAssets.keys
                                .filterNotNull()
                                .sortedByDescending { it }
                                .forEach { year ->
                                    val yearAssets = groupedAssets[year]!!
                                    val header = HeaderItem(year.toLong(), year.toString())
                                    val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                                    
                                    yearAssets.forEach { asset ->
                                        listRowAdapter.add(asset.toCard())
                                    }
                                    
                                    rowsAdapter.add(ListRow(header, listRowAdapter))
                                }
                            
                            Timber.d("Grouped into ${rowsAdapter.size()} years")
                        }
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Exception in OnThisDayBrowseFragment")
            }
        }
    }

    private fun onItemClicked(card: Card) {
        // Navegación al slider de fotos cuando se hace click en una foto
        ioScope.launch {
            try {
                // Cargar todos los assets para el slider
                val result = apiClient.onThisDayAssets(
                    1,
                    1000,
                    PreferenceManager.get(FILTER_CONTENT_TYPE)
                )
                
                result.fold(
                    { error -> Timber.e("Error loading assets for slider: $error") },
                    { assets ->
                        val toSliderItems = assets.toSliderItems(keepOrder = true, mergePortrait = false)
                        
                        requireActivity().runOnUiThread {
                            findNavController().navigate(
                                AlbumDetailsFragmentDirections.actionToPhotoSlider(
                                    MediaSliderConfiguration(
                                        toSliderItems.indexOfFirst { it.ids().contains(card.id) },
                                        PreferenceManager.get(SLIDER_INTERVAL),
                                        PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                                        isVideoSoundEnable = true,
                                        toSliderItems,
                                        null, // loadMore
                                        { }, // updatePosition
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
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error navigating to slider")
            }
        }
    }
}
