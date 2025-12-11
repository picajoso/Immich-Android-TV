package nl.giejay.android.tv.immich.shared.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.mediaslider.model.MetaDataType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun List<Asset>.toSliderItems(keepOrder: Boolean, mergePortrait: Boolean): List<SliderItemViewHolder> {
    if (!mergePortrait) {
        return this.map { SliderItemViewHolder(it.toSliderItem()) }
    }
    if (!keepOrder) {
        val portraits = this.filter { it.isPortraitImage() }
        val queue = portraits.toMutableList()
        val portraitSliders = mutableListOf<SliderItemViewHolder>()

        while (queue.isNotEmpty()) {
            val first = queue.removeAt(0)
            val firstDate = first.fileModifiedAt?.time

            val second = if (first.people?.isNotEmpty() == true && firstDate != null) {
                queue.filter { candidate ->
                    candidate.people?.size == first.people.size &&
                            candidate.people.firstOrNull()?.id == first.people.first().id &&
                            candidate.exifInfo?.city != null && first.exifInfo?.city != null &&
                            candidate.exifInfo.city == first.exifInfo.city &&
                            candidate.exifInfo.dateTimeOriginal != null
                }.maxByOrNull { candidate ->
                    kotlin.math.abs(candidate.exifInfo!!.dateTimeOriginal!!.time - firstDate)
                } ?: queue.filter { candidate ->
                    candidate.people?.firstOrNull()?.id == first.people.first().id &&
                            candidate.exifInfo?.dateTimeOriginal != null
                }.maxByOrNull { candidate ->
                    kotlin.math.abs(candidate.exifInfo!!.dateTimeOriginal!!.time - firstDate)
                }
            } else null

            if (second != null) {
                portraitSliders.add(SliderItemViewHolder(first.toSliderItem(), second.toSliderItem()))
                queue.remove(second)
            } else {
                portraitSliders.add(SliderItemViewHolder(first.toSliderItem(), queue.removeFirstOrNull()?.toSliderItem()))
            }
        }

        val landscapeItems = this - portraits.toSet()
        return (landscapeItems.map { SliderItemViewHolder(it.toSliderItem()) } + portraitSliders).shuffled()
    }

    val queue = this.toMutableList()
    val items = mutableListOf<SliderItemViewHolder>()

    while (queue.isNotEmpty()) {
        val first = queue.removeAt(0)
        val second = queue.firstOrNull()

        if (first.isPortraitImage() && second?.isPortraitImage() == true) {
            items.add(SliderItemViewHolder(first.toSliderItem(), second.toSliderItem()))
            queue.remove(second)
        } else {
            items.add(SliderItemViewHolder(first.toSliderItem()))
        }
    }
    return items
}

fun Asset.toSliderItem(): SliderItem {
    return SliderItem(this.id,
        ApiUtil.getFileUrl(this.id, this.type),
        SliderItemType.valueOf(this.type.uppercase()),
        mapOf(MetaDataType.DATE to this.exifInfo?.dateTimeOriginal?.let { formatDate(it) },
            MetaDataType.CITY to this.exifInfo?.city,
            MetaDataType.COUNTRY to this.exifInfo?.country,
            MetaDataType.ALBUM_NAME to this.albumName,
            MetaDataType.DESCRIPTION to this.exifInfo?.description,
            MetaDataType.FILENAME to this.originalFileName,
            MetaDataType.PEOPLE to this.people?.map { it.name }?.filter { it?.isNotBlank() == true }?.joinToString(", "),
            MetaDataType.FILEPATH to this.originalPath,
            MetaDataType.CAMERA to (listOf(this.exifInfo?.make, this.exifInfo?.model)).filterNotNull().joinToString(" ")),
        ApiUtil.getThumbnailUrl(this.id, "preview"),
        this.isFavorite // <--- PASAMOS EL VALOR
    )
}

private fun formatDate(date: Date): String {
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    return SimpleDateFormat("dd/MM/yyyy", locale).format(date)
}

fun Asset.isPortraitImage(): Boolean {
    return (this.exifInfo?.orientation == 6 || (this.exifInfo?.exifImageWidth != null && this.exifInfo.exifImageHeight != null && this.exifInfo.exifImageWidth - 100 < this.exifInfo.exifImageHeight)) && this.type == SliderItemType.IMAGE.toString()
}

fun List<Asset>.toCards(): List<Card> {
    return this.map {
        it.toCard()
    }
}

// CORREGIDA: Ahora detecta VIDEO y FAVORITOS
fun Asset.toCard(): Card {
    return Card(
        title = this.originalFileName ?: "",
        description = this.exifInfo?.description ?: "",
        id = this.id,
        thumbnailUrl = ApiUtil.getThumbnailUrl(this.id, "thumbnail"),
        backgroundUrl = ApiUtil.getThumbnailUrl(this.id, "preview"),
        isVideo = this.type == "VIDEO",
        
        // --- ¡¡ESTA LÍNEA FALTABA!! ---
        isFavorite = this.isFavorite
        // ------------------------------
    )
}
