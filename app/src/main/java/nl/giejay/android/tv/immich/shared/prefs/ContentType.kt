package nl.giejay.android.tv.immich.shared.prefs

enum class ContentType:EnumWithTitle {
    ALL {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("All")
        }
    }, IMAGE {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Image")
        }
    }, VIDEO {
        override fun getTitle(): String {
            return nl.giejay.android.tv.immich.shared.util.Tr.t("Video")
        }
    }
}