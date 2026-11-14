package nl.giejay.android.tv.immich.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.core.content.res.ResourcesCompat
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.UiStyle
import timber.log.Timber

/**
 * Downloadable fonts (Inter for Apple, Outfit for Google TV) through the Google Fonts provider
 * (the font family XML files in res/font). Downloads are asynchronous and best effort: until a font arrives (or when the
 * provider is not available on the device) a system sans-serif typeface is used.
 * Call [preload] early (Application.onCreate); views created later pick up the cached typeface.
 */
object UiFonts {
    private val loaded = mutableMapOf<Int, Typeface>()
    private val requested = mutableSetOf<Int>()

    private fun fontRes(style: UiStyle, title: Boolean): Int = when (style) {
        UiStyle.APPLE -> if (title) R.font.inter_bold else R.font.inter_regular
        UiStyle.GOOGLE_TV -> if (title) R.font.outfit_medium else R.font.outfit_regular
    }

    fun preload(context: Context, style: UiStyle) {
        listOf(true, false).forEach { request(context.applicationContext, fontRes(style, it)) }
    }

    private fun request(context: Context, resId: Int) {
        if (loaded.containsKey(resId) || !requested.add(resId)) return
        try {
            ResourcesCompat.getFont(context, resId, object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) {
                    loaded[resId] = typeface
                }

                override fun onFontRetrievalFailed(reason: Int) {
                    Timber.w("Could not download font $resId, reason $reason, using fallback")
                    // allow a retry on next preload
                    requested.remove(resId)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Timber.w(e, "Font request failed")
            requested.remove(resId)
        }
    }

    /** Typeface for titles: Apple bold, Google TV medium. */
    fun title(style: UiStyle): Typeface {
        return loaded[fontRes(style, true)] ?: when (style) {
            UiStyle.APPLE -> Typeface.create("sans-serif", Typeface.BOLD)
            UiStyle.GOOGLE_TV -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    /** Typeface for body text. */
    fun body(style: UiStyle): Typeface {
        return loaded[fontRes(style, false)] ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }
}
