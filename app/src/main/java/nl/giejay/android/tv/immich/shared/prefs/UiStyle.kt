package nl.giejay.android.tv.immich.shared.prefs

/**
 * The two selectable interface styles (Settings > View settings > Interface style).
 * Stored by enum name, see [UI_STYLE].
 */
enum class UiStyle : EnumWithTitle {
    APPLE {
        override fun getTitle(): String = "Apple"
    },
    GOOGLE_TV {
        override fun getTitle(): String = "Google TV"
    };

    companion object {
        fun valueOfSafe(name: String, default: UiStyle): UiStyle {
            return entries.find { it.toString() == name } ?: default
        }
    }
}
