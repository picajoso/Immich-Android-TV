package nl.giejay.android.tv.immich.assets

import java.util.concurrent.ConcurrentHashMap

// Memoria global compartida entre todas las pantallas de la app
object FavoriteCache {
    // Mapa: ID del Asset -> esFavorito (true/false)
    val overrides = ConcurrentHashMap<String, Boolean>()
}
