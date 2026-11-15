package nl.giejay.android.tv.immich.onthisday

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.assets.PhotoRow
import nl.giejay.android.tv.immich.assets.PhotoRowsFragment
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SIMILAR_ASSETS_YEARS_BACK
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * On this day: one horizontal row per year in which photos were taken on today's date, newest
 * year first, with the first photo of the latest year as faded hero in the top-right corner.
 * Data logic is unchanged (ApiClient.onThisDayAssets, years back derived from the oldest asset).
 */
class OnThisDayBrowseFragment : PhotoRowsFragment() {

    override val pageTitle: String get() = nl.giejay.android.tv.immich.shared.util.Tr.t("On this day")
    override val showHero: Boolean get() = true
    override val emptyText: String get() = nl.giejay.android.tv.immich.shared.util.Tr.t("No photos from this day in previous years")

    override fun subtitle(): String {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        return SimpleDateFormat(pattern, locale).format(Date())
    }

    override suspend fun loadRows(client: ApiClient): List<PhotoRow> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val fallbackYears = PreferenceManager.get(SIMILAR_ASSETS_YEARS_BACK)
        val yearsBack = client.getOldestAsset().fold(
            { error ->
                Timber.e("Error loading oldest asset: $error")
                fallbackYears
            },
            { oldest ->
                val oldestYear = yearOf(oldest)
                if (oldestYear != null) currentYear - oldestYear else fallbackYears
            }
        )

        // today's result does not change until tomorrow: cached per day and content filter
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val cacheKey = "onthisday_${today}_${PreferenceManager.get(FILTER_CONTENT_TYPE)}"
        val cachedAssets = nl.giejay.android.tv.immich.shared.cache.SwrCache.readList<Asset>(cacheKey)?.first
        val assets = cachedAssets ?: client.onThisDayAssets(1, 1000, PreferenceManager.get(FILTER_CONTENT_TYPE), yearsBack)
            .fold(
                { error ->
                    Timber.e("Error loading 'On this day' assets: $error")
                    emptyList<Asset>()
                },
                { it }
            ).also {
                // never keep a result that lost years to failed requests: it would stay wrong all day
                if (it.isNotEmpty() && !client.onThisDayIncomplete) nl.giejay.android.tv.immich.shared.cache.SwrCache.writeList(cacheKey, it)
            }
        val byYear = assets.groupBy { yearOf(it) }

        return (0..yearsBack).mapNotNull { offset ->
            val year = currentYear - offset
            val yearAssets = byYear[year]
            if (yearAssets.isNullOrEmpty()) {
                null
            } else {
                PhotoRow(rowTitle(offset, year, topCities(yearAssets, 1).firstOrNull(), yearAssets.size), yearAssets)
            }
        }
    }

    private fun rowTitle(yearsAgo: Int, year: Int, city: String?, count: Int): CharSequence {
        val ago = when (yearsAgo) {
            0 -> nl.giejay.android.tv.immich.shared.util.Tr.t("This year")
            1 -> nl.giejay.android.tv.immich.shared.util.Tr.t("1 year ago")
            else -> nl.giejay.android.tv.immich.shared.util.Tr.t("%d years ago").replace("%d", yearsAgo.toString())
        }
        val text = SpannableStringBuilder(ago)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(tokens.text), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("  ·  ").append(year.toString())
        if (!city.isNullOrBlank()) text.append("  ·  ").append(city)
        text.append("  ·  ").append(count.toString())
        return text
    }
}
