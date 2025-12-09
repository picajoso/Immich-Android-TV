// CardPresenter.kt - AGREGAR ESTO AL PRINCIPIO DEL ARCHIVO O MODIFICAR LA LÍNEA 71
package nl.giejay.android.tv.immich.card

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.presenter.AbstractPresenter
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.GRID_COLUMN_COUNT
import timber.log.Timber

open class CardPresenter(context: Context, style: Int = R.style.DefaultCardTheme) :
    AbstractPresenter<ImageCardView, ICard>(ContextThemeWrapper(context, style)) {

    // Variables para definir qué hacer al pulsar
    var onLongClick: ((ICard) -> Unit)? = null
    var onClick: ((ICard) -> Unit)? = null

    companion object {
        private const val VIDEO_ICON_TAG = "VIDEO_OVERLAY_ICON"
    }

    override fun onCreateView(): ImageCardView {
        val cardView = ImageCardView(context)
        
        val cols = try {
            PreferenceManager.get(GRID_COLUMN_COUNT)
        } catch (e: Exception) { 4 }

        val (widthRes, heightRes) = when (cols) {
            3 -> Pair(R.dimen.card_width_3_cols, R.dimen.card_height_3_cols)
            5 -> Pair(R.dimen.card_width_5_cols, R.dimen.card_height_5_cols)
            else -> Pair(R.dimen.card_width_4_cols, R.dimen.card_height_4_cols)
        }

        val width = context.resources.getDimensionPixelSize(widthRes)
        val height = context.resources.getDimensionPixelSize(heightRes)
        cardView.setMainImageDimensions(width, height)

        val radius = context.resources.getDimension(R.dimen.card_corner_radius)
        cardView.mainImageView!!.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        cardView.mainImageView!!.clipToOutline = true
        cardView.setBackgroundColor(Color.TRANSPARENT)

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true

        return cardView
    }

    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        cardView.tag = card
        
        // 1. Configurar CLIC NORMAL con manejo de errores
        cardView.setOnClickListener {
            Timber.d("CLICK EN CardPresenter para card: ${card.id}")
            try {
                onClick?.invoke(card)
                Timber.d("onClick ejecutado correctamente")
            } catch (e: Exception) {
                Timber.e(e, "Error al ejecutar onClick para card: ${card.id}")
                // Intenta mostrar un error al usuario
                try {
                    if (context is Activity) {
                        android.widget.Toast.makeText(
                            context, 
                            "Error al abrir la foto", 
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (toastEx: Exception) {
                    Timber.e(toastEx, "Error al mostrar toast")
                }
            }
        }

        // 2. Configurar CLIC LARGO con manejo de errores
        cardView.setOnLongClickListener {
            Timber.d("LONG CLICK EN CardPresenter para card: ${card.id}")
            try {
                onLongClick?.invoke(card)
            } catch (e: Exception) {
                Timber.e(e, "Error al ejecutar onLongClick para card: ${card.id}")
            }
            true 
        }
        
        // Limpiar listener de teclas conflictivo
        cardView.setOnKeyListener(null)

        val spacing = cardView.resources.getDimensionPixelSize(R.dimen.card_spacing)
        if (cardView.layoutParams is ViewGroup.MarginLayoutParams) {
            val params = cardView.layoutParams as ViewGroup.MarginLayoutParams
            params.setMargins(spacing, spacing, spacing, spacing)
            cardView.layoutParams = params
        }

        val showNames = try {
            PreferenceManager.get(nl.giejay.android.tv.immich.shared.prefs.SHOW_FILE_NAMES_GRID)
        } catch (e: Exception) { true }

        if (showNames) {
            cardView.cardType = BaseCardView.CARD_TYPE_INFO_UNDER
            cardView.titleText = card.title
            cardView.contentText = card.description
        } else {
            cardView.cardType = BaseCardView.CARD_TYPE_MAIN_ONLY
            cardView.titleText = null
            cardView.contentText = null
        }
        
        val layers = mutableListOf<android.graphics.drawable.Drawable>()

        if (card is nl.giejay.android.tv.immich.card.Card && card.isVideo) {
            val playIcon = context.getDrawable(android.R.drawable.ic_media_play)?.mutate()
            playIcon?.setTint(Color.WHITE)
            if (playIcon != null) {
                val layerPlay = LayerDrawable(arrayOf(playIcon))
                layerPlay.setLayerGravity(0, Gravity.BOTTOM or Gravity.END)
                layerPlay.setLayerInset(0, 0, 20, 20, 20)
                layers.add(layerPlay)
            }
        }

        if (card is nl.giejay.android.tv.immich.card.Card && card.isFavorite) {
            val starIcon = context.getDrawable(nl.giejay.android.tv.immich.R.drawable.ic_favorite_white_filled_24dp)?.mutate()
            if (starIcon != null) {
                val layerStar = LayerDrawable(arrayOf(starIcon))
                layerStar.setLayerGravity(0, Gravity.TOP or Gravity.END)
                layerStar.setLayerInset(0, 0, 10, 10, 10)
                layers.add(layerStar)
            }
        }

        if (layers.isNotEmpty()) {
            val finalDrawable = LayerDrawable(layers.toTypedArray())
            for (i in layers.indices) {
                val item = layers[i] as LayerDrawable
                finalDrawable.setLayerGravity(i, item.getLayerGravity(0))
                finalDrawable.setLayerInset(i, item.getLayerInsetLeft(0), item.getLayerInsetTop(0), item.getLayerInsetRight(0), item.getLayerInsetBottom(0))
            }
            cardView.mainImageView!!.foreground = finalDrawable
        } else {
            cardView.mainImageView!!.foreground = null
        }
        
        loadImage(card, cardView)
        setSelected(cardView, card.selected)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        super.onUnbindViewHolder(viewHolder)
        if(context is Activity && context.isFinishing){ return }
        try {
            val imgView = (viewHolder.view as ImageCardView).mainImageView!!
            Glide.with(context).clear(imgView)
            imgView.foreground = null
            viewHolder.view.setOnClickListener(null)
            viewHolder.view.setOnLongClickListener(null)
        } catch (e: IllegalArgumentException){
            Timber.e(e)
        }
    }

    open fun loadImage(card: ICard, cardView: ImageCardView) {
        val url = card.thumbnailUrl
        val imageView = cardView.mainImageView!!
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        if (!url.isNullOrBlank()) {
            if(url.startsWith("http")){
                Glide.with(context).asBitmap().load(url).into(imageView)
            } else {
                val resourceId = context.resources.getIdentifier(url, "drawable", context.packageName)
                if (resourceId != 0) imageView.setImageResource(resourceId)
            }
        } else {
            imageView.setImageDrawable(null)
        }
    }

    private fun setSelected(imageCardView: ImageCardView, selected: Boolean) {
        if(selected){
            imageCardView.mainImageView!!.background = context.getDrawable(R.drawable.border)
        } else {
            imageCardView.mainImageView!!.background = null
        }
    }
}
