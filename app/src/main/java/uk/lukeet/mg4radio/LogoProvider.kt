package uk.lukeet.mg4radio

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves station artwork to Android Auto, which only loads media-item artwork from content:// URIs
 * and only for the rows on screen — so logos load lazily as the list scrolls. Falls back to the
 * generated name tile when a station has no logo anywhere.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val name = uri.getQueryParameter("name").orEmpty()
        val file = StationLogos.get(context).file(name) ?: tile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun tile(uri: Uri): File {
        val station = Station(
            key = uri.getQueryParameter("key").orEmpty(),
            name = uri.getQueryParameter("name").orEmpty(),
            type = uri.getQueryParameter("type")?.toIntOrNull() ?: Station.TYPE_DAB,
            frequencyKhz = uri.getQueryParameter("khz")?.toIntOrNull() ?: 0,
            playing = false, favourite = false, nowPlaying = "",
        )
        val dir = File(context!!.cacheDir, "tiles").apply { mkdirs() }
        val file = File(dir, "${station.key.hashCode()}.png")
        if (!file.exists()) {
            val tmp = File(dir, "${file.name}.tmp")
            tmp.outputStream().use { StationArtwork.forStation(station, null).compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        }
        return file
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val AUTHORITY = "uk.lukeet.mg4radio.logos"

        fun uriFor(station: Station): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).path("logo")
            .appendQueryParameter("key", station.key)
            .appendQueryParameter("name", station.name.trim())
            .appendQueryParameter("type", station.type.toString())
            .appendQueryParameter("khz", station.frequencyKhz.toString())
            .build()
    }
}
