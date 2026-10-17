package uk.lukeet.mg4radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder

data class Station(
    val key: String,
    val name: String,
    val type: Int,
    val frequencyKhz: Int,
    val playing: Boolean,
    val favourite: Boolean,
    val nowPlaying: String,
) {
    val isDab get() = type == TYPE_DAB
    val bandLabel get() = when (type) {
        TYPE_DAB -> "DAB"
        TYPE_AM -> "$frequencyKhz kHz AM"
        else -> "%.1f FM".format(frequencyKhz / 1000f)
    }

    companion object {
        const val TYPE_AM = 1
        const val TYPE_FM = 2
        const val TYPE_DAB = 4
    }
}

/**
 * Talks to DiAuto's radio bridge on the head unit (port 30519) over the car hotspot. Requests are
 * pinned to that Wi-Fi network: a hotspot without internet is not Android's default route.
 */
class CarRadioClient(private val context: Context) {

    /** The last request failed because a VPN without bypass (e.g. Tailscale) owns this app's traffic. */
    @Volatile var blockedByVpn = false
        private set

    private fun carNetwork(): Pair<Network, String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (BuildConfig.RADIO_HOST.isNotEmpty()) return cm.activeNetwork?.let { it to BuildConfig.RADIO_HOST }
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val gateway = cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress ?: continue
            return network to gateway
        }
        return null
    }

    private fun get(path: String): ByteArray? {
        val (network, host) = carNetwork() ?: return null.also { Log.w(TAG, "no Wi-Fi network with a gateway") }
        return try {
            blockedByVpn = false
            val conn = network.openConnection(URL("http://$host:$PORT$path")) as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 4_000
            try {
                if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $path via $network to $host failed: $e")
            blockedByVpn = e.message?.contains("EPERM") == true
            null
        }
    }

    private fun json(path: String) = get(path)?.let { JSONObject(String(it)) }

    fun state(): Station? = json("/radio/state")?.takeIf { it.has("key") }?.let { parse(it) }

    fun logo(): Bitmap? = get("/radio/logo.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    fun favourites(): List<Station> = list("/radio/favourites")

    fun stations(type: Int): List<Station> = list("/radio/stations?type=$type")

    fun play() = get("/radio/play") != null
    fun pause() = get("/radio/pause") != null
    fun next() = get("/radio/next") != null
    fun previous() = get("/radio/previous") != null
    fun tune(key: String) = get("/radio/tune?key=${URLEncoder.encode(key, "UTF-8")}") != null

    private fun list(path: String): List<Station> {
        val array = json(path)?.optJSONArray("stations") ?: return emptyList()
        return (0 until array.length()).map { parse(array.getJSONObject(it)) }.distinctBy { it.key }
    }

    private fun parse(o: JSONObject) = Station(
        key = o.getString("key"),
        name = o.optString("name"),
        type = o.optInt("type"),
        frequencyKhz = o.optInt("frequencyKhz"),
        playing = o.optBoolean("playing"),
        favourite = o.optBoolean("favourite"),
        nowPlaying = o.optString("nowPlaying"),
    )

    companion object {
        const val PORT = 30519
        private const val TAG = "MG4Radio"
    }
}
