package nl.giejay.android.tv.immich.shared.cache

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Stale-while-revalidate store for the sections that rarely change (albums, people, timeline...).
 * The last result is kept in memory and on disk (cache dir, so Android may clear it), per server.
 * Screens show it at once and then ask the server whether anything changed: for big payloads with
 * an ETag ([etag]) the server can simply answer "304 not modified" and nothing is downloaded.
 */
object SwrCache {
    class Entry(val json: String, val etag: String?)

    // dates as epoch millis, so the files do not depend on the locale
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonSerializer<Date> { d, _, _ -> JsonPrimitive(d.time) })
        .registerTypeAdapter(Date::class.java, JsonDeserializer { j, _, _ -> Date(j.asLong) })
        .create()

    private val mem = ConcurrentHashMap<String, Entry>()

    private fun server(): String {
        val host = try { PreferenceManager.get(HOST_NAME) } catch (e: Exception) { "" }
        return MessageDigest.getInstance("MD5").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
    }

    private fun file(key: String): File? {
        val ctx = ImmichApplication.appContext ?: return null
        val dir = File(ctx.cacheDir, "swr").apply { mkdirs() }
        return File(dir, "${server()}_$key.json")
    }

    fun read(key: String): Entry? {
        val id = "${server()}_$key"
        mem[id]?.let { return it }
        return try {
            val f = file(key)?.takeIf { it.exists() } ?: return null
            val text = f.readText()
            val nl = text.indexOf('\n')
            Entry(text.substring(nl + 1), text.substring(0, nl).ifEmpty { null }).also { mem[id] = it }
        } catch (e: Exception) {
            Timber.w(e, "SwrCache: could not read $key")
            null
        }
    }

    fun write(key: String, json: String, etag: String? = null) {
        mem["${server()}_$key"] = Entry(json, etag)
        try {
            file(key)?.writeText("${etag ?: ""}\n$json")
        } catch (e: Exception) {
            Timber.w(e, "SwrCache: could not write $key")
        }
    }

    inline fun <reified T> readList(key: String): Pair<List<T>, String?>? {
        val e = read(key) ?: return null
        return try {
            gson.fromJson<List<T>>(e.json, TypeToken.getParameterized(List::class.java, T::class.java).type) to e.etag
        } catch (ex: Exception) {
            null
        }
    }

    fun <T> writeList(key: String, list: List<T>, etag: String? = null) = write(key, gson.toJson(list), etag)

    fun readCounts(key: String): MutableMap<String, String> {
        val e = read(key) ?: return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<String, String>>(e.json, object : TypeToken<MutableMap<String, String>>() {}.type) ?: mutableMapOf()
        } catch (ex: Exception) {
            mutableMapOf()
        }
    }

    fun writeMap(key: String, map: Map<String, String>) = write(key, gson.toJson(map))
}
