package nl.giejay.android.tv.immich.screensaver

import nl.giejay.android.tv.immich.shared.prefs.EnumWithTitle

enum class ScreenSaverType : EnumWithTitle {
    ALBUMS {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Albums")
        }
    },
    RANDOM {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Random")
        }
    },
    RECENT {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Recent")
        }
    },
    SIMILAR_TIME_PERIOD {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Seasonal")
        }
    }
}