package nl.giejay.android.tv.immich.assets

import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SIMILAR_ASSETS_PERIOD_DAYS
import java.util.Calendar

/**
 * Seasonal: photos taken around today's date in earlier years, one row per year (newest first).
 * Data logic is the existing ApiClient.similarAssets (a window around today per year back).
 * The chip only names the current season.
 */
class SeasonalFragment : PhotoRowsFragment() {

    override val pageTitle: String get() = "Seasonal"
    override val emptyText: String get() = "No photos from this time of year"

    override fun chips(): List<String> = listOf(currentSeason())

    override fun subtitle(): String =
        "${currentSeason()} across the years  ·  photos within ${PreferenceManager.get(SIMILAR_ASSETS_PERIOD_DAYS)} days of today"

    private fun currentSeason(): String {
        return when (Calendar.getInstance().get(Calendar.MONTH)) {
            Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "Winter"
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "Spring"
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "Summer"
            else -> "Autumn"
        }
    }

    override suspend fun loadRows(client: ApiClient): List<PhotoRow> {
        val assets = client.similarAssets(1, 60, PreferenceManager.get(FILTER_CONTENT_TYPE))
            .fold({ emptyList<Asset>() }, { it })
        val byYear = assets.mapNotNull { a -> yearOf(a)?.let { year -> year to a } }
            .groupBy({ it.first }, { it.second })
        return byYear.keys.sortedDescending().map { year ->
            val list = byYear.getValue(year)
            val title = (listOf(year.toString()) + topCities(list, 2)).joinToString("  \u00B7  ")
            PhotoRow(title, list.sortedByDescending { dateOf(it)?.time ?: 0L })
        }
    }
}
