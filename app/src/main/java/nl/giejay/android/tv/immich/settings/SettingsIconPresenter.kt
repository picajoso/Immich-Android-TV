package nl.giejay.android.tv.immich.settings

import android.content.Context
import android.view.View
import androidx.leanback.widget.ImageCardView
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.card.ICard
import nl.giejay.android.tv.immich.card.CardPresenter

/**
 * Simple presenter implementation to represent settings icon as cards.
 * Overrides onBindViewHolder to ALWAYS show text.
 */
class SettingsIconPresenter(context: Context) : CardPresenter(context, R.style.IconCardTheme) {

    // --- AÑADIDO: Sobrescribimos esto para forzar que el texto se vea siempre ---
    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        // Configuramos datos básicos
        cardView.tag = card
        
        // FORZAMOS VISIBILIDAD: El menú de ajustes siempre debe tener texto
        val infoArea = cardView.findViewById<View>(androidx.leanback.R.id.info_field)
        infoArea?.visibility = View.VISIBLE
        
        cardView.titleText = card.title
        cardView.contentText = card.description

        // Llamamos a la carga de imagen (que ya estaba personalizada abajo)
        loadImage(card, cardView)
        // No llamamos a super.onBindViewHolder porque queremos controlar nosotros la visibilidad
    }
    // --------------------------------------------------------------------------

    override fun onCreateView(): ImageCardView {
        val imageCardView: ImageCardView = super.onCreateView()
        imageCardView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setImageBackground(imageCardView, R.color.settings_card_background_focussed)
            } else {
                setImageBackground(imageCardView, R.color.settings_card_background)
            }
        }
        setImageBackground(imageCardView, R.color.settings_card_background)
        return imageCardView
    }

    override fun loadImage(card: ICard, cardView: ImageCardView) {
        val resourceId = context.resources
            .getIdentifier(
                card.thumbnailUrl,
                "drawable", context.packageName
            )
        Glide.with(context)
            .asBitmap()
            .load(resourceId)
            .into(cardView.mainImageView!!)
    }

    private fun setImageBackground(imageCardView: ImageCardView, colorId: Int) {
        imageCardView.setBackgroundColor(context.resources.getColor(colorId))
    }
}
