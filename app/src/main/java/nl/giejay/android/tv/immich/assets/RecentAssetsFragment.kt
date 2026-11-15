package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.View
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.RECENT_ASSETS_MONTHS_BACK

/** Recent: "Recently added" (uploaded lately) or "Recently taken" (newest capture dates). */
class RecentAssetsFragment : GenericAssetFragment() {
    override val gridTitle: String? = "Recent"

    private var added = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHeaderTabs("Recently added", "Recently taken", if (added) 0 else 1) { index ->
            val wantAdded = index == 0
            if (wantAdded != added) {
                added = wantAdded
                clearState()
                allPagesLoaded = false
                fetchInitialItems()
            }
        }
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {
        return if (added) {
            apiClient.recentlyAddedAssets(page, pageCount, currentFilter, PreferenceManager.get(RECENT_ASSETS_MONTHS_BACK).toLong())
        } else {
            apiClient.recentlyTakenAssets(page, pageCount, currentFilter)
        }
    }
}
