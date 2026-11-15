package nl.giejay.android.tv.immich.api.model

import com.google.gson.annotations.SerializedName

/**
 * Respuesta "Columnar" del servidor.
 * Recibimos listas de propiedades y las convertimos en objetos Asset.
 */
data class BucketResponse(
    val id: List<String>,
    val isImage: List<Boolean>,
    val deviceAssetId: List<String?>?,
    val originalPath: List<String?>?,
    val originalFileName: List<String?>?,
    // El JSON tiene city y country, los usaremos para crear un Exif parcial
    val city: List<String?>?,
    val country: List<String?>?,
    // UTC time the photo was taken, e.g. "2026-02-28T23:47:50.438"; drives the date pill of the grids
    val fileCreatedAt: List<String?>? = null
) {
    private fun takenAt(index: Int): java.util.Date? {
        val text = fileCreatedAt?.getOrNull(index) ?: return null
        return try {
            java.util.Date.from(java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC))
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }

    fun toAssets(): List<Asset> {
        val assets = mutableListOf<Asset>()
        val count = id.size

        for (i in 0 until count) {
            // 1. Determinar tipo
            val typeStr = if (isImage.getOrElse(i) { true }) "IMAGE" else "VIDEO"

            // 2. Construir ExifInfo parcial (Solo ciudad y país, que es lo que tenemos en esta vista)
            val cityVal = city?.getOrNull(i)
            val countryVal = country?.getOrNull(i)
            
            val taken = takenAt(i)
            val exif = if (cityVal != null || countryVal != null || taken != null) {
                AssetExifInfo(
                    description = null,
                    orientation = null,
                    exifImageWidth = null,
                    exifImageHeight = null,
                    city = cityVal,
                    country = countryVal,
                    dateTimeOriginal = taken,
                    make = null,
                    model = null
                )
            } else null

            // 3. Crear el Asset usando EXACTAMENTE los campos que tienes en tu Asset.kt
            val asset = Asset(
                id = id[i],
                type = typeStr,
                deviceAssetId = deviceAssetId?.getOrNull(i),
                exifInfo = exif,
                fileModifiedAt = null, // Pasamos null para evitar errores de parseo de fecha por ahora
                albumName = null,
                people = null,
                tags = null,
                originalPath = originalPath?.getOrNull(i),
                originalFileName = originalFileName?.getOrNull(i)
            )
            assets.add(asset)
        }
        return assets
    }
}
