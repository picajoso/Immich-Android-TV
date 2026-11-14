package nl.giejay.android.tv.immich.shared.util

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.mediaslider.config.MediaSliderConfiguration

/** Opens the viewer on [clickedId] with a fixed list of [assets] (no paging). */
fun Fragment.openSliderFor(assets: List<Asset>, clickedId: String) {
    val sliderItems = assets.toSliderItems(keepOrder = true, mergePortrait = false)
    findNavController().navigate(
        AlbumDetailsFragmentDirections.actionToPhotoSlider(
            MediaSliderConfiguration(
                sliderItems.indexOfFirst { it.ids().contains(clickedId) }.coerceAtLeast(0),
                PreferenceManager.get(SLIDER_INTERVAL),
                PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                isVideoSoundEnable = true,
                sliderItems,
                null,
                { },
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
