package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    val isVideo: Boolean = false, // <--- NUEVO CAMPO
    override var selected: Boolean = false
) : ICard
