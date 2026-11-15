package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import nl.giejay.android.tv.immich.ImmichApplication

/**
 * Translations. UI text is written in English in the code and looked up here: `Tr.t("Edit menu")`
 * finds the string resource `t_edit_menu`, which exists in `values/` (English) and in
 * `values-<lang>/` for every supported language. The language is the one selected in Android TV;
 * a text without a translation shows the English original.
 *
 * Adding a language = adding a `values-xx/strings_tr.xml` with the same names.
 */
object Tr {
    private val ids = HashMap<String, Int>()

    /** Resource name of an English text: lowercase, non-alphanumerics folded to "_", max 60 chars. */
    fun key(text: String): String =
        "t_" + text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60)

    fun t(text: String): String = ImmichApplication.appContext?.let { t(it, text) } ?: text

    fun t(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val id = ids.getOrPut(text) { context.resources.getIdentifier(key(text), "string", context.packageName) }
        return if (id != 0) context.getString(id) else text
    }

    /** "1 photo" / "1.234 photos": the pattern is translated, %d is replaced by the grouped number. */
    fun count(singular: String, plural: String, n: Int): String =
        t(if (n == 1) singular else plural).replace("%d", nl.giejay.android.tv.immich.ui.UiFormat.count(n))

    fun photos(n: Int): String = count("%d photo", "%d photos", n)
    fun people(n: Int): String = count("%d person", "%d people", n)
}
