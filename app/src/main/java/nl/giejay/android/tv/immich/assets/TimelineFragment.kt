package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController // <--- IMPORTANTE
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Solo configuramos el botón de búsqueda
        setOnSearchClickedListener {
            findNavController().navigate(R.id.action_timeline_to_picker)
        }

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
