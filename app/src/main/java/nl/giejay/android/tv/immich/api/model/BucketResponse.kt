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
    val country: List<String?>?
) {
    fun toAssets(): List<Asset> {
        val assets = mutableListOf<Asset>()
        val count = id.size

        for (i in 0 until count) {
            // 1. Determinar tipo
            val typeStr = if (isImage.getOrElse(i) { true }) "IMAGE" else "VIDEO"

            // 2. Construir ExifInfo parcial (Solo ciudad y país, que es lo que tenemos en esta vista)
            val cityVal = city?.getOrNull(i)
            val countryVal = country?.getOrNull(i)
            
            val exif = if (cityVal != null || countryVal != null) {
                AssetExifInfo(
                    description = null,
                    orientation = null,
                    exifImageWidth = null,
                    exifImageHeight = null,
                    city = cityVal,
                    country = countryVal,
                    dateTimeOriginal = null,
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
