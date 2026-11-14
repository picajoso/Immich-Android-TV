package nl.giejay.android.tv.immich.api.model

import java.util.Date

data class AssetExifInfo(
    val description: String?,
    val orientation: Int?,
    val exifImageWidth: Int?,
    val exifImageHeight: Int?,
    val city: String?,
    val country: String?,
    val dateTimeOriginal: Date?,
    val make: String?,
    val model: String?,
    // exposure / file details for the viewer info panel (all optional, absent on partial exif)
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val focalLength: Double? = null,
    val iso: Int? = null,
    val exposureTime: String? = null,
    val fileSizeInByte: Long? = null
)

data class Tag(
    val color: String?,
    val createdAt: Date,
    val name: String,
    val value: String
)

data class Asset(
    val id: String,
    val type: String,
    val deviceAssetId: String?,
    val exifInfo: AssetExifInfo?,
    val fileModifiedAt: Date?,
    val albumName: String?,
    val people: List<Person>?,
    val tags: List<Tag>?,
    val originalPath: String?,
    val originalFileName: String?,
    val isFavorite: Boolean = false // <--- ¡ESTE ERA EL CAMPO QUE FALTABA!
)
