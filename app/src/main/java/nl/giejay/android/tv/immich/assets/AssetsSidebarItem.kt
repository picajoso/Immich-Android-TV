package nl.giejay.android.tv.immich.assets

import nl.giejay.android.tv.immich.api.model.Bucket

/**
 * Representa un elemento en la barra de navegación lateral combinada (Años y Meses).
 */
sealed class AssetsSidebarItem {
    /**
     * Título para un año específico (ej: "2024"). No es interactivo.
     */
    data class YearItem(val year: String) : AssetsSidebarItem()

    /**
     * Fila para un mes (Bucket) específico. Es interactivo.
     * @param bucket El objeto Bucket de Immich que contiene la información del mes.
     * @param isSelected Indica si este mes está actualmente seleccionado y cargado en la cuadrícula de assets.
     */
    data class MonthItem(val bucket: Bucket, val isSelected: Boolean = false) : AssetsSidebarItem()
}
