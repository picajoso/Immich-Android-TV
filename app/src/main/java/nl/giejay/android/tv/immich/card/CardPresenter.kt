package nl.giejay.android.tv.immich.card

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.presenter.AbstractPresenter
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber

/**
 * Presenter ESTÁNDAR para Fotos y Álbumes.
 * - Soporta ocultar nombres.
 * - Añade icono de Play usando FOREGROUND (sin romper el layout).
 */
open class CardPresenter(context: Context, style: Int = R.style.DefaultCardTheme) :
    AbstractPresenter<ImageCardView, ICard>(ContextThemeWrapper(context, style)) {

    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        cardView.tag = card
        
        // 1. LEER PREFERENCIA
        val showNames = try {
            PreferenceManager.get(nl.giejay.android.tv.immich.shared.prefs.SHOW_FILE_NAMES_GRID)
        } catch (e: Exception) {
            true
        }

        // 2. CONFIGURAR EL TIPO DE TARJETA
        if (showNames) {
            cardView.cardType = BaseCardView.CARD_TYPE_INFO_UNDER
            cardView.titleText = card.title
            cardView.contentText = card.description
        } else {
            cardView.cardType = BaseCardView.CARD_TYPE_MAIN_ONLY
            cardView.titleText = null
            cardView.contentText = null
        }
        
        // 3. ICONO DE VÍDEO (Usando Foreground con !!)
        if (card is Card && card.isVideo) {
            val playIcon = context.getDrawable(android.R.drawable.ic_media_play)?.mutate()
            playIcon?.setTint(Color.WHITE)
            
            if (playIcon != null) {
                // Capa con el icono
                val layerDrawable = LayerDrawable(arrayOf(playIcon))
                
                // Posicionamiento: Abajo a la derecha
                layerDrawable.setLayerGravity(0, Gravity.BOTTOM or Gravity.END)
                // Margen
                layerDrawable.setLayerInset(0, 0, 20, 20, 20) 
                
                // APLICAMOS AL PRIMER PLANO (Aquí estaba el error, añadimos !!)
                cardView.mainImageView!!.foreground = layerDrawable
            }
        } else {
            // Limpiamos si no es vídeo (Añadimos !!)
            cardView.mainImageView!!.foreground = null
        }
        
        loadImage(card, cardView)
        setSelected(cardView, card.selected)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        super.onUnbindViewHolder(viewHolder)
        if(context is Activity && context.isFinishing){
            return
        }
        try {
            val imgView = (viewHolder.view as ImageCardView).mainImageView!!
            Glide.with(context).clear(imgView)
            // Limpiamos el foreground también (Añadimos !!)
            imgView.foreground = null
        } catch (e: IllegalArgumentException){
            Timber.e(e)
        }
    }

    open fun loadImage(card: ICard, cardView: ImageCardView) {
        val url = card.thumbnailUrl
        // Añadimos !! aquí también por seguridad
        val imageView = cardView.mainImageView!!
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        if (!url.isNullOrBlank()) {
            if(url.startsWith("http")){
                Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .into(imageView)
            } else {
                val resourceId = context.resources.getIdentifier(url, "drawable", context.packageName)
                if (resourceId != 0) imageView.setImageResource(resourceId)
            }
        } else {
            imageView.setImageDrawable(null)
        }
    }

    override fun onCreateView(): ImageCardView {
        return ImageCardView(context)
    }

    private fun setSelected(imageCardView: ImageCardView, selected: Boolean) {
        if(selected){
            imageCardView.mainImageView!!.background = context.getDrawable(R.drawable.border)
        } else {
            imageCardView.mainImageView!!.background = null
        }
    }
}
