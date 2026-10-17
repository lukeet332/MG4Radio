package uk.lukeet.mg4radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Real station logos from the internet: TuneIn's 600px square logos first, then radio-browser.info
 * (which covers the BBC, absent from TuneIn since 2019). Cached in memory and on disk, so each
 * station is downloaded once; stations with no logo anywhere are not asked about again this session.
 */
class StationLogos private constructor(context: Context) {
    private val dir = File(context.cacheDir, "logos").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(40)
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Lookups run in parallel batches as Android Auto scrolls the list, but not all at once. */
    private val downloads = Semaphore(6)

    fun find(name: String): Bitmap? {
        val query = name.trim().lowercase()
        memory.get(query)?.let { return it }
        val file = file(name) ?: return null
        return BitmapFactory.decodeFile(file.path)?.also { memory.put(query, it) }
    }

    /** The cached logo file for [name], downloading it first if needed; null when there is none. */
    fun file(name: String): File? {
        val query = name.trim().lowercase()
        if (query.isEmpty() || query in misses) return null
        val file = File(dir, "${query.hashCode()}.img")
        if (file.exists()) return file
        synchronized(locks.getOrPut(query) { Any() }) {
            if (file.exists()) return file
            downloads.acquire()
            try {
                val reached = BooleanArray(1)
                val search = searchName(name)
                val bytes = (tuneInUrl(search, reached) ?: radioBrowserUrl(search, reached))?.let { fetch(it) }
                if (bytes == null || BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                    // A network failure is retried on the next visit; "no logo anywhere" is not.
                    if (reached[0]) misses += query
                    return null
                }
                Log.i("MG4Radio", "downloaded logo for $name")
                val tmp = File(dir, "${file.name}.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
                return file
            } finally {
                downloads.release()
            }
        }
    }

    /** Station labels abbreviate: "BBC R2" and "BBC Radio2" are "BBC Radio 2", "BBC Radio5Live" is "BBC Radio 5 Live". */
    private fun searchName(name: String) = name.trim()
        .replace(Regex("\\bR(\\d)L\\b"), "Radio $1 Live")
        .replace(Regex("\\bR(\\d)\\b"), "Radio $1")
        .replace(Regex("Radio(\\d)"), "Radio $1")
        .replace(Regex("(\\d)([A-Z][a-z])"), "$1 $2")

    private fun tuneInUrl(name: String, reached: BooleanArray): String? {
        val body = fetch("https://opml.radiotime.com/Search.ashx?types=station&render=json&query=${enc(name)}")
            ?.let { reached[0] = true; JSONObject(String(it)).optJSONArray("body") } ?: return null
        val stations = (0 until body.length()).map { body.getJSONObject(it) }
            .filter { it.optString("item") == "station" && it.optString("image").isNotEmpty() }
        val best = stations.firstOrNull { it.optString("text").equals(name, ignoreCase = true) }
            ?: stations.firstOrNull { it.optString("text").startsWith(name, ignoreCase = true) }
            ?: return null
        // logoq is 145px; logog is the 600px version of the same image.
        return best.getString("image").substringBefore("?").replace("/logoq.", "/logog.").replace("http://", "https://")
    }

    private fun radioBrowserUrl(name: String, reached: BooleanArray): String? {
        val list = fetch("https://de1.api.radio-browser.info/json/stations/search?countrycode=GB&order=clickcount&reverse=true&limit=10&hidebroken=true&name=${enc(name)}")
            ?.let { reached[0] = true; JSONArray(String(it)) } ?: return null
        return (0 until list.length()).map { list.getJSONObject(it).optString("favicon") }
            .firstOrNull { url -> listOf(".png", ".jpg", ".jpeg").any { url.substringBefore("?").endsWith(it, ignoreCase = true) } }
            ?.replace(Regex("(tunein\\.com/s\\d+)q\\.png"), "$1g.png")
    }

    private fun fetch(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 6_000
        conn.setRequestProperty("User-Agent", "MG4Radio/1.0")
        try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w("MG4Radio", "logo lookup $url failed: $e")
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        @Volatile private var instance: StationLogos? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: StationLogos(context.applicationContext).also { instance = it }
        }
    }
}
