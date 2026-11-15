package nl.giejay.android.tv.immich.api

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.model.AlbumDetails
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.api.model.Folder
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.api.model.SearchRequest
import nl.giejay.android.tv.immich.api.service.ApiService
import nl.giejay.android.tv.immich.api.util.ApiUtil.executeAPICall
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.RECENT_ASSETS_MONTHS_BACK
import nl.giejay.android.tv.immich.shared.prefs.SIMILAR_ASSETS_PERIOD_DAYS
import nl.giejay.android.tv.immich.shared.prefs.SIMILAR_ASSETS_YEARS_BACK
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import nl.giejay.android.tv.immich.api.model.BucketResponse
import nl.giejay.android.tv.immich.api.model.DeleteAssetsRequest
import nl.giejay.android.tv.immich.api.model.UpdateAssetRequest
import timber.log.Timber
import java.util.Calendar
import java.util.Date

data class ApiClientConfig(
    val hostName: String,
    val apiKey: String,
    val disableSslVerification: Boolean,
    val debugMode: Boolean
)

class ApiClient(private val config: ApiClientConfig) {
    companion object ApiClient {
        private var apiClient: nl.giejay.android.tv.immich.api.ApiClient? = null
        fun getClient(config: ApiClientConfig): nl.giejay.android.tv.immich.api.ApiClient {
            if (config != apiClient?.config) {
                apiClient = ApiClient(config)
            }
            return apiClient!!
        }

        // --- AÑADIR ESTA FUNCIÓN ---
        fun invalidate() {
            apiClient = null
        }
        // ---------------------------

        // Immich 3.x rejects datetimes without an offset (HTTP 400, "expected ISO 8601 datetime"); the
        // search dates are UTC, like the time buckets ("2026-11-01T00:00:00.000Z")
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    }

    private val retrofit = Retrofit.Builder()
        .client(ApiClientFactory.getClient(config.disableSslVerification, config.apiKey, config.debugMode))
        .addConverterFactory(GsonConverterFactory.create())
        .baseUrl("${config.hostName}/api/")
        .build()

    private val service: ApiService = retrofit.create(ApiService::class.java)

    // --- FUNCIONES DE ASSETS (FAVORITOS Y DETALLE) ---
    
    suspend fun getAsset(id: String): Either<String, Asset> {
        return executeAPICall(200) { service.getAsset(id) }
    }

    /** Number of photos of one person (People cards; requested for the visible cards only). */
    suspend fun personStatistics(personId: String): Either<String, nl.giejay.android.tv.immich.api.model.PersonStatistics> {
        return executeAPICall(200) { service.personStatistics(personId) }
    }

    /** The signed-in user (name shown in the Google TV drawer). */
    suspend fun currentUser(): Either<String, nl.giejay.android.tv.immich.api.model.UserMe> {
        return executeAPICall(200) { service.currentUser() }
    }

    /** Number of images / videos in the library (side menu counter). */
    suspend fun assetStatistics(isFavorite: Boolean? = null): Either<String, nl.giejay.android.tv.immich.api.model.AssetStatistics> {
        return executeAPICall(200) { service.assetStatistics(isFavorite) }
    }

    suspend fun toggleFavorite(assetId: String, isFavorite: Boolean): Either<String, Asset> {
        return executeAPICall(200) {
            service.updateAsset(
                id = assetId,
                body = UpdateAssetRequest(isFavorite)
            )
        }
    }

    suspend fun moveToTrash(assetId: String): Either<String, Unit> {
        Timber.d("API: moveToTrash() called with assetId: $assetId")
        return executeAPICall(204) {
            Timber.d("API: Calling service.deleteAssets(assetId)")
            service.deleteAssets(DeleteAssetsRequest(ids = listOf(assetId)))
        }
    }
    // ------------------------------------------------

    /** Albums owned by the user. */
    suspend fun listMyAlbums(): Either<String, List<Album>> = executeAPICall(200) { service.listAlbums() }

    /** Albums shared with / by the user. */
    suspend fun listSharedAlbums(): Either<String, List<Album>> = executeAPICall(200) { service.listAlbums(true) }

    suspend fun listAlbums(): Either<String, List<Album>> {
        return executeAPICall(200) { service.listAlbums() }.flatMap { albums ->
            return executeAPICall(200) { service.listAlbums(true) }.map { sharedAlbums ->
                albums + sharedAlbums
            }
        }
    }

    suspend fun listPeople(): Either<String, List<Person>> {
        return executeAPICall(200) { service.listPeople() }.map { response -> response.people.filter { !it.name.isNullOrBlank() } }
    }

    suspend fun listAssetsFromAlbum(albumId: String): Either<String, AlbumDetails> {
        return executeAPICall(200) {
            val response = service.listAssetsFromAlbum(albumId)
            val album = response.body()
            val assets = album!!.assets.filter(excludeByTag()).map { it.copy(albumName = album.albumName) }
            Response.success(album.copy(assets = assets))
        }
    }

    suspend fun recentAssets(page: Int, pageCount: Int, contentType: ContentType): Either<String, List<Asset>> {
        val now = LocalDateTime.now()
        return listAssets(page, pageCount, true, "desc",
            contentType = contentType, fromDate = now.minusMonths(PreferenceManager.get(RECENT_ASSETS_MONTHS_BACK).toLong()), endDate = now)
            .map { it.shuffled() }
    }

    suspend fun similarAssets(page: Int, pageCount: Int, contentType: ContentType): Either<String, List<Asset>> {
        val now = LocalDateTime.now()
        val map: List<Either<String, List<Asset>>> = (0 until PreferenceManager.get(SIMILAR_ASSETS_YEARS_BACK)).toList().map {
            listAssets(page,
                pageCount,
                true,
                "desc",
                fromDate = now.minusDays((PreferenceManager.get(SIMILAR_ASSETS_PERIOD_DAYS) / 2).toLong()).minusYears(it.toLong()),
                endDate = now.plusDays((PreferenceManager.get(SIMILAR_ASSETS_PERIOD_DAYS) / 2).toLong()).minusYears(it.toLong()),
                contentType = contentType)
        }
        if (map.all { it.isLeft() }) {
            return map.first()
        }
        return Either.Right(map.flatMap { it.getOrElse { emptyList() } }.shuffled())
    }

    private fun getDateFromAsset(asset: Asset): Date? {
        return asset.exifInfo?.dateTimeOriginal ?: asset.fileModifiedAt
    }

    suspend fun onThisDayAssets(
        page: Int,
        pageCount: Int,
        contentType: ContentType,
        yearsBack: Int? = null
    ): Either<String, List<Asset>> {
        val now = LocalDateTime.now()
        val today = now.dayOfMonth
        val currentMonth = now.monthValue
    
        Timber.d("OnThisDay: Buscando fotos del día $today del mes $currentMonth")
    
        val map: List<Either<String, List<Asset>>> = (0 until (yearsBack ?: PreferenceManager.get(SIMILAR_ASSETS_YEARS_BACK))).toList().map { yearOffset ->
            val fromDate = now.withHour(0).withMinute(0).withSecond(0).minusYears(yearOffset.toLong())
            val endDate = now.withHour(23).withMinute(59).withSecond(59).minusYears(yearOffset.toLong())
        
            Timber.d("OnThisDay: Año ${now.year - yearOffset} - Buscando desde $fromDate hasta $endDate")
        
            listAssets(page,
                pageCount,
                true,
                "desc",
                fromDate = fromDate,
                endDate = endDate,
                contentType = contentType)
        }
    
        if (map.all { it.isLeft() }) {
            return map.first()
        }
    
        val allAssets = map.flatMap { it.getOrElse { emptyList() } }
        Timber.d("OnThisDay: Total assets antes de filtrar: ${allAssets.size}")
    
        // Filtrar para asegurar que solo tenemos fotos del día y mes actual
        val filteredAssets = allAssets.filter { asset ->
            getDateFromAsset(asset)?.let { date ->
                val cal = Calendar.getInstance()
                cal.time = date
                val assetDay = cal.get(Calendar.DAY_OF_MONTH)
                val assetMonth = cal.get(Calendar.MONTH) + 1 // Calendar.MONTH es 0-indexed
            
                val matches = assetDay == today && assetMonth == currentMonth
                if (!matches) {
                    Timber.d("OnThisDay: Descartando asset del día $assetDay/$assetMonth")
                }
                matches
            } ?: false
        }
    
        Timber.d("OnThisDay: Total assets después de filtrar: ${filteredAssets.size}")
    
        return Either.Right(filteredAssets)
    }

    suspend fun listAssets(page: Int,
                       pageCount: Int,
                       random: Boolean = false,
                       order: String = "desc",
                       personIds: List<UUID> = emptyList(),
                       fromDate: LocalDateTime? = null,
                       endDate: LocalDateTime? = null,
                       contentType: ContentType,
                       isFavorite: Boolean? = null): Either<String, List<Asset>> {  // ← AÑADIR PARÁMETRO
    val searchRequest = SearchRequest(page,
        pageCount,
        order,
        if (contentType == ContentType.ALL) null else contentType.toString(),
        personIds,
        endDate?.format(dateTimeFormatter),
        fromDate?.format(dateTimeFormatter),
        true,              // ← withExif (mantener existente)
        isFavorite)        // ← AÑADIR ESTE PARÁMETRO
        return (if (random) {
            executeAPICall(200) { service.randomAssets(searchRequest) }
        } else {
            executeAPICall(200) { service.listAssets(searchRequest) }.map { res -> res.assets.items }
        }).map { it.filter(excludeByTag()) }.map {
            it.forEach { asset ->
                Timber.d("listAssets: Asset ${asset.originalFileName} - Date: ${getDateFromAsset(asset)}")
            }
            val excludedAlbums = PreferenceManager.get(EXCLUDE_ASSETS_IN_ALBUM)
            if (excludedAlbums.isNotEmpty()) {
                val excludedAssets =
                    excludedAlbums.toList().flatMap { albumId -> listAssetsFromAlbum(albumId).getOrNull()?.assets ?: emptyList() }.map { it.id }
                it.filterNot { asset -> excludedAssets.contains(asset.id) }
            } else {
                it
            }
        }
    }

    private fun excludeByTag() = { asset: Asset ->
        asset.tags?.none { t -> t.name == "exclude_immich_tv" } ?: true
    }

   suspend fun listBuckets(albumId: String, order: PhotosOrder): Either<String, List<Bucket>> {
        val safeAlbumId = if (albumId.isBlank()) null else albumId

        return executeAPICall(200) {
            service.listBuckets(
                albumId = safeAlbumId, 
                order = if (order == PhotosOrder.OLDEST_NEWEST) "asc" else "desc"
            )
        }
    }

   suspend fun getAssetsForBucket(albumId: String, bucket: String, order: PhotosOrder): Either<String, List<Asset>> {
        val safeAlbumId = if (albumId.isBlank()) null else albumId

        return executeAPICall<BucketResponse>(200) {
            service.getBucket(
                albumId = safeAlbumId,
                timeBucket = bucket,
                order = if (order == PhotosOrder.OLDEST_NEWEST) "asc" else "desc"
            )
        }.map { response -> 
            response.toAssets() 
        }
    }

    /** First (oldest) asset of a calendar month, used as cover in the timeline overview. */
    suspend fun firstAssetOfMonth(year: Int, month: Int): Either<String, Asset?> {
        val from = LocalDateTime.of(year, month, 1, 0, 0, 0)
        val end = from.plusMonths(1).minusSeconds(1)
        return listAssets(1, 1, false, "asc", fromDate = from, endDate = end, contentType = ContentType.ALL)
            .map { it.firstOrNull() }
    }

    suspend fun listFolders(): Either<String, Folder> {
        return executeAPICall(200) {
            service.getUniquePaths()
        }.map { paths ->
            return Either.Right(createRootFolder(Folder("", mutableListOf(), null), paths))
        }
    }

    private fun createRootFolder(parent: Folder, paths: List<String>): Folder {
        paths.forEach { path ->
            val directories = path.split("/")
            createFolders(directories, parent)
        }
        return parent
    }

    private fun createFolders(paths: List<String>, currentParent: Folder): Folder {
        if (paths.isEmpty()) {
            return currentParent
        }
        val createdChild = Folder(paths.first(), mutableListOf(), currentParent)
        val alreadyOwnedChild = currentParent.hasPath(paths.first())
        if (alreadyOwnedChild != null) {
            return createFolders(paths.drop(1), alreadyOwnedChild)
        }
        currentParent.children.add(createdChild)
        return createFolders(paths.drop(1), createdChild)
    }

    suspend fun listAssetsForFolder(folder: String): Either<String, List<Asset>> {
        return executeAPICall(200) {
            service.getAssetsForPath(folder)
        }.map { it.filter(excludeByTag()) }
    }

    suspend fun getOldestAsset(): Either<String, Asset> {
        return listAssets(1, 1, order = "asc", contentType = ContentType.ALL).map { it.first() }
    }

suspend fun listFavoriteAssets(page: Int, pageCount: Int, contentType: ContentType): Either<String, List<Asset>> {
    return listAssets(
        page = page, 
        pageCount = pageCount, 
        random = false, 
        order = "desc", 
        contentType = contentType,
        isFavorite = true  // ← USAR FILTRO DEL SERVIDOR
    )
    // Ya no necesita .map { it.filter { asset -> asset.isFavorite } }
}
}
