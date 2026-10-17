package uk.lukeet.mg4radio

import android.content.Context

/** Stations played most recently, newest first, so lists (and Android Auto's "For you") lead with them. */
class RecentStations(context: Context) {
    private val prefs = context.getSharedPreferences("recent_stations", Context.MODE_PRIVATE)

    fun record(key: String) {
        val keys = listOf(key) + load().filter { it != key }
        prefs.edit().putString(KEY, keys.take(MAX).joinToString("\n")).apply()
    }

    /** [stations] with the playing one first, then recently played, then the rest in the car's order. */
    fun order(stations: List<Station>, playingKey: String?): List<Station> {
        val rank = (listOfNotNull(playingKey) + load()).distinct().withIndex().associate { it.value to it.index }
        return stations.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    private fun load() = prefs.getString(KEY, "").orEmpty().split("\n").filter { it.isNotEmpty() }

    private companion object {
        const val KEY = "keys"
        const val MAX = 20
    }
}
