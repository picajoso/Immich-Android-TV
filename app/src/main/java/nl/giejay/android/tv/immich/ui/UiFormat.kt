package nl.giejay.android.tv.immich.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Number formatting shared by the redesigned screens. */
object UiFormat {
    /** "1.240": thousands are always grouped (also 4-digit numbers, which e.g. Spanish would not). */
    fun count(value: Int): String {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        return DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).format(value)
    }
}
