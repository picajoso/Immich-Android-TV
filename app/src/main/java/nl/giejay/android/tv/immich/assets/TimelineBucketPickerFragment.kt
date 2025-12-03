package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment // USAMOS ESTE
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
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.MonthPresenter // Asegúrate de usar el nuevo MonthPresenter
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimelineBucketPickerFragment : RowsSupportFragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

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

        // Usamos ListRowPresenter para tener filas con cabeceras (Años)
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        setupClient()
        loadBuckets()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card = item as Card
            val bucketId = card.id as? String ?: return@OnItemViewClickedListener
            
            val bundle = android.os.Bundle().apply {
                putString("timeBucket", bucketId)
            }

            // IMPORTANTE: Usamos la acción que definimos en homeFragment
            findNavController().navigate(
                R.id.action_picker_to_timeline, 
                bundle
            )
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

    private fun loadBuckets() {
        ioScope.launch {
            val result = apiClient.listBuckets("", PhotosOrder.NEWEST_OLDEST)
            result.fold(
                { error -> Timber.e("Error loading buckets: $error") },
                { buckets -> processBucketsByYear(buckets) }
            )
        }
    }

    private fun processBucketsByYear(buckets: List<Bucket>) {
        val bucketsByYear = buckets.groupBy { bucket ->
            try {
                bucket.timeBucket.substring(0, 4)
            } catch (e: Exception) {
                "Otros"
            }
        }

        requireActivity().runOnUiThread {
            rowsAdapter.clear()
            // Usamos MonthPresenter para las tarjetas de colores
            val cardPresenter = MonthPresenter(requireContext())

            bucketsByYear.forEach { (year, yearBuckets) ->
                val header = HeaderItem(year)
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                
                val cards = yearBuckets.map { bucket ->
                    val monthTitle = formatMonthOnly(bucket.timeBucket)
                    Card(
                        id = bucket.timeBucket,
                        title = monthTitle,
                        description = "(${bucket.count})",
                        thumbnailUrl = null,
                        backgroundUrl = null
                    )
                }
                listRowAdapter.addAll(0, cards)
                rowsAdapter.add(ListRow(header, listRowAdapter))
            }
        }
    }

    private fun formatMonthOnly(rawDate: String): String {
        return try {
            val date = LocalDate.parse(rawDate)
            val formatter = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
            date.format(formatter).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } catch (e: Exception) {
            rawDate
        }
    }
}
