package nl.giejay.android.tv.immich.card

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.presenter.AbstractPresenter

/**
 * Presenter ESPECÍFICO para el selector de Meses.
 * - Fuerza tamaño rectangular pequeño (300x100).
 * - Genera fondo de color basado en el texto.
 */
class MonthPresenter(context: Context, style: Int = R.style.DefaultCardTheme) :
    AbstractPresenter<ImageCardView, ICard>(ContextThemeWrapper(context, style)) {

    override fun onCreateView(): ImageCardView {
        val cardView = ImageCardView(context)
        // AQUÍ es donde definimos el tamaño "chip" o "botón" solo para esta pantalla
        cardView.setMainImageDimensions(300, 100)
        return cardView
    }

    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        cardView.tag = card
        cardView.titleText = card.title
        cardView.contentText = card.description

        // Generamos el color basado en el título (ej: "Noviembre 2024")
        val color = generateColor(card.title ?: "")
        
        cardView.mainImageView!!.setImageDrawable(ColorDrawable(color))
        cardView.mainImageView!!.scaleType = ImageView.ScaleType.CENTER
        
        setSelected(cardView, card.selected)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        super.onUnbindViewHolder(viewHolder)
        (viewHolder.view as ImageCardView).mainImageView!!.setImageDrawable(null)
    }

    private fun generateColor(str: String): Int {
        val hash = str.hashCode()
        val r = (hash and 0xFF0000 shr 16)
        val g = (hash and 0x00FF00 shr 8)
        val b = (hash and 0x0000FF)
        // Colores pastel oscuros para que el texto blanco resalte
        return Color.rgb((r + 64) / 2, (g + 64) / 2, (b + 64) / 2)
    }

    private fun setSelected(imageCardView: ImageCardView, selected: Boolean) {
        if(selected){
            imageCardView.mainImageView!!.background = context.getDrawable(R.drawable.border)
        } else {
            imageCardView.mainImageView!!.background = null
        }
    }
}
