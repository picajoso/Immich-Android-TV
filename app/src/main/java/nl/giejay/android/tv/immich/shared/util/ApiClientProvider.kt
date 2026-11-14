package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** The ApiClient for the stored server settings, or null when not logged in. */
fun currentApiClient(): ApiClient? {
    if (!PreferenceManager.isLoggedId()) return null
    return ApiClient.getClient(
        ApiClientConfig(
            PreferenceManager.get(HOST_NAME),
            PreferenceManager.get(API_KEY),
            PreferenceManager.get(DISABLE_SSL_VERIFICATION),
            PreferenceManager.get(DEBUG_MODE)
        )
    )
}
