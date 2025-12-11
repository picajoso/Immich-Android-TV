package nl.giejay.android.tv.immich.onthisday

import nl.giejay.android.tv.immich.api.model.Asset
import java.util.Date

data class OnThisDay(
    val date: Date,
    val assets: List<Asset>
)
