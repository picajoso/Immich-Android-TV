package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.format.DateFormat
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController // <--- IMPORTANTE
import androidx.navigation.navOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.shared.util.currentApiClient
import nl.giejay.android.tv.immich.ui.UiStyleProvider
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import arrow.core.Either
import nl.giejay.android.tv.immich.R // <--- IMPORTANTE (Tu R.id...)
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.shared.prefs.ALL_ASSETS_SORTING
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder

/**
 * Timeline grid that shows assets for a selected time bucket.
 * If no bucket is provided, it falls back to the most recent bucket.
 */
class TimelineFragment : GenericAssetFragment() {

    private var currentBucket: Bucket? = null

    /** Month view: continuous grid titled with the month ("November 2026"). */
    override val gridTitle: String?
        get() = monthLabel(arguments?.getString("timeBucket"), "MMMMyyyy") ?: "Timeline"

    private fun monthLabel(timeBucket: String?, skeleton: String): String? {
        if (timeBucket == null || timeBucket.length < 7) return null
        val year = timeBucket.substring(0, 4).toIntOrNull() ?: return null
        val month = timeBucket.substring(5, 7).toIntOrNull() ?: return null
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, 1)
        val f = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(cal.time)
    }

    // the month view reads from the first photo of the month onwards
    override fun sortItems(items: List<Asset>): List<Asset> = items.sortedWith(PhotosOrder.OLDEST_NEWEST.sort)
    
    // Si estamos viendo un bucket específico (Timeline), cargamos todo de golpe para poder ir al final.
    // Si es la vista general, usamos paginación normal.
    override val fetchCount: Int
        get() = if (currentBucket != null) Int.MAX_VALUE else super.fetchCount

    override fun setData(assets: List<Asset>) {
        if (currentBucket != null) {
            // Como cargamos todo el bucket de una vez (la API no pagina buckets por ahora),
            // marcamos que ya no hay más páginas para evitar bucles infinitos al ser fetchCount MAX_VALUE
            allPagesLoaded = true 
        }
        super.setData(assets)
    }

    private fun setupMonthButtons(root: View) {
        val bucket = arguments?.getString("timeBucket") ?: return
        val client = currentApiClient() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.listBuckets("", PhotosOrder.NEWEST_OLDEST) }
            val list = result.getOrNull() ?: return@launch
            val index = list.indexOfFirst { it.timeBucket.take(7) == bucket.take(7) }
            if (index < 0 || !isAdded) return@launch
            val newer = list.getOrNull(index - 1)
            val older = list.getOrNull(index + 1)
            val actions = root.findViewById<View>(R.id.grid_header_actions) ?: return@launch
            actions.visibility = View.VISIBLE
            bindMonthButton(root.findViewById(R.id.grid_action_prev), older?.timeBucket, "\u2039 ")
            bindMonthButton(root.findViewById(R.id.grid_action_next), newer?.timeBucket, null)
        }
    }

    private fun bindMonthButton(button: TextView?, timeBucket: String?, prefix: String?) {
        if (button == null || timeBucket == null) return
        val tokens = UiStyleProvider.current()
        val ctx = button.context
        val name = monthLabel(timeBucket, "MMM") ?: return
        button.text = if (prefix != null) prefix + name else "$name \u203A"
        UiStyleProvider.applyBody(button, tokens, tokens.bodySizeSp, tokens.text)
        fun pill(stroke: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiStyleProvider.dp(ctx, 500).toFloat()
            setColor(tokens.pillBg)
            if (stroke) setStroke(UiStyleProvider.dp(ctx, 2f), tokens.focusRing)
        }
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(true))
            addState(intArrayOf(), pill(false))
        }
        button.setPadding(UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, 6), UiStyleProvider.dp(ctx, 12), UiStyleProvider.dp(ctx, 6))
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            findNavController().navigate(
                R.id.photoGridFragment,
                bundleOf("timeBucket" to timeBucket),
                navOptions { popUpTo(R.id.photoGridFragment) { inclusive = true } }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMonthButtons(view)

        // No configuramos botón de búsqueda (lupa) para mantener la interfaz limpia

        // 1. CONFIGURACIÓN DEL BOTÓN DE BÚSQUEDA (Lupa)
        // Al pulsarlo, navegamos al Picker usando la acción del nav_graph.xml
        //setOnSearchClickedListener {
        //    findNavController().navigate(R.id.action_timeline_to_picker)
        //}

        // 2. LECTURA DE ARGUMENTOS (Si venimos del Picker)
        //val bucketFromArgs = arguments?.getString("timeBucket")
        //if (bucketFromArgs != null) {
            // Si nos han pasado una fecha, forzamos ese bucket
        //    currentBucket = Bucket(count = 0, timeBucket = bucketFromArgs)
            // Opcional: Limpiamos el argumento para que no persista si rotamos pantalla
        //    arguments?.remove("timeBucket")
        //}
    }

    override fun getSortingKey(): EnumByTitlePref<PhotosOrder> = ALL_ASSETS_SORTING

    override fun getFilterKey(): EnumByTitlePref<ContentType> = FILTER_CONTENT_TYPE


   override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {

        val order = PhotosOrder.NEWEST_OLDEST

        // --- CORRECCIÓN AQUÍ ---
        // Leemos el argumento justo ahora, antes de decidir qué cargar.
        // Esto asegura que si venimos del selector, currentBucket tenga valor.
        val bucketFromArgs = arguments?.getString("timeBucket")
        if (bucketFromArgs != null) {
            // Si hay un argumento, forzamos que este sea el bucket actual
            currentBucket = Bucket(count = 0, timeBucket = bucketFromArgs)
        }
        // -----------------------

        // Si currentBucket sigue siendo null (carga inicial sin argumentos), buscamos el más reciente
        if (currentBucket == null) {
            val bucketsEither = apiClient.listBuckets("", order)
            when (bucketsEither) {
                is Either.Left -> return bucketsEither
                is Either.Right -> {
                    val bucket = bucketsEither.value.firstOrNull()
                    if (bucket == null) {
                        return Either.Right(emptyList())
                    }
                    currentBucket = bucket
                }
            }
        }

        // Cargamos los assets del bucket actual (ya sea el de argumentos o el más reciente)
        return currentBucket?.let { bucket ->
            apiClient.getAssetsForBucket("", bucket.timeBucket, order)
        } ?: Either.Right(emptyList())
    }
}
