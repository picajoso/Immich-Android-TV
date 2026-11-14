package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.View
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset

class RandomAssetsFragment : GenericAssetFragment() {
    override val gridTitle: String? = "Random"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHeaderAction("Shuffle") { shuffle() }
    }

    /** Throws the current grid away and loads a new random selection. */
    private fun shuffle() {
        clearState()
        allPagesLoaded = false
        fetchInitialItems()
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {
        return apiClient.listAssets(page,
            pageCount,
            random = true,
            contentType = currentFilter)
    }
}
