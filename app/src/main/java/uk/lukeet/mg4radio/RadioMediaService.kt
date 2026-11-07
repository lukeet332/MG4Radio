package uk.lukeet.mg4radio

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Auto media app for the car's own radio. Android Auto draws its standard media player from
 * this session: station name, now-playing text and cover art, with play/pause and next/previous
 * driving the head unit's tuner through DiAuto's radio bridge. Audio stays on the car's radio.
 */
class RadioMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: MediaSessionCompat
    private lateinit var client: CarRadioClient
    private lateinit var logos: StationLogos
    private lateinit var recent: RecentStations
    private var webLogo: Bitmap? = null
    private var shownStation: Station? = null
    private var queueType: Int? = null
    private var queueKeys: List<String> = emptyList()
    private var pollJob: Job? = null
    private var shownKey: String? = null
    private var shownNowPlaying: String? = null
    private var shownPlaying: Boolean? = null
    private var shownHasLogo = false
    private var logoAttempts = 0
    private var reachable = true
    private val reachability = Reachability()

    override fun onCreate() {
        super.onCreate()
        client = CarRadioClient(this)
        logos = StationLogos.get(this)
        recent = RecentStations(this)
        session = MediaSessionCompat(this, "MG4Radio").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(if (reachable) POLL_MS else POLL_UNREACHABLE_MS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i("MG4Radio", "root requested by $clientPackageName hints=${rootHints?.keySet()?.associateWith { rootHints.get(it) }}")
        // Android Auto's "For you" card asks for suggestions: that is the DAB list.
        val suggested = rootHints?.getBoolean(BrowserRoot.EXTRA_SUGGESTED) == true
        return BrowserRoot(if (suggested) DAB else ROOT, Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
        })
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        scope.launch {
            val items = when (parentId) {
                // One top-level list, so Android Auto's "For you" card can only draw on DAB stations.
                ROOT -> listOf(folder(DAB, getString(R.string.dab_stations)))
                DAB -> recent.order(client.stations(Station.TYPE_DAB), shownStation?.key).map { stationItem(it) } +
                    folder(FM, getString(R.string.fm_stations)) +
                    (if (client.favourites().isNotEmpty()) listOf(folder(FAVOURITES, getString(R.string.favourites))) else emptyList())
                FAVOURITES -> client.favourites().map { stationItem(it) }
                FM -> recent.order(client.stations(Station.TYPE_FM), shownStation?.key).map { stationItem(it) }
                else -> emptyList()
            }
            Log.i("MG4Radio", "browse $parentId: ${items.size} items")
            result.sendResult(items.toMutableList())
        }
    }

    private fun stationItem(station: Station) = MediaItem(stationDescription(station), MediaItem.FLAG_PLAYABLE)

    private fun stationDescription(station: Station) = MediaDescriptionCompat.Builder()
        .setMediaId(station.key)
        .setTitle(station.name.ifBlank { station.bandLabel })
        .setSubtitle(station.bandLabel)
        .setIconUri(LogoProvider.uriFor(station))
        .build()

    private fun folder(id: String, title: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = command(playing = true) { client.play() }
        override fun onPause() = command(playing = false) { client.pause() }
        override fun onStop() = command(playing = false) { client.pause() }
        override fun onSkipToNext() = command(playing = true) { client.next() }
        override fun onSkipToPrevious() = command(playing = true) { client.previous() }
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = command(playing = true) { client.tune(mediaId) }
        override fun onSkipToQueueItem(id: Long) {
            val key = queueKeys.getOrNull(id.toInt()) ?: return
            command(playing = true) { client.tune(key) }
        }
    }

    /** Shows the expected state straight away, sends the command, then re-reads the car's state. */
    private fun command(playing: Boolean, action: () -> Boolean) {
        publishState(if (playing) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_PAUSED)
        shownPlaying = null
        scope.launch {
            action()
            delay(SETTLE_MS)
            refresh()
        }
    }

    @Synchronized
    private fun refresh() {
        val station = client.state()
        if (station == null) {
            if (!reachability.onPoll(ok = false)) return
            reachable = false
            shownPlaying = null
            publishState(PlaybackStateCompat.STATE_ERROR)
            return
        }
        reachability.onPoll(ok = true)
        reachable = true
        val stationChanged = station.key != shownKey
        if (stationChanged) {
            shownHasLogo = false
            logoAttempts = 0
            webLogo = null
            recent.record(station.key)
            lookUpLogo(station)
            if (station.type != queueType) loadQueue(station.type)
        }
        // The car loads a DAB logo a moment after tuning, so keep asking for a few polls.
        val logo = if (station.isDab && webLogo == null && !shownHasLogo && logoAttempts < LOGO_ATTEMPTS) {
            logoAttempts++
            client.logo()
        } else {
            null
        }
        if (stationChanged || station.nowPlaying != shownNowPlaying || logo != null) {
            publishMetadata(station, logo)
            shownKey = station.key
            shownStation = station
            shownNowPlaying = station.nowPlaying
            if (logo != null) shownHasLogo = true
        }
        if (station.playing != shownPlaying) {
            shownPlaying = station.playing
            publishState(if (station.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        }
    }

    /** The player's station list (Android Auto's queue view): every station on the band playing. */
    private fun loadQueue(type: Int) {
        queueType = type
        val stations = client.stations(type)
        queueKeys = stations.map { it.key }
        session.setQueueTitle(getString(if (type == Station.TYPE_DAB) R.string.dab_stations else R.string.fm_stations))
        session.setQueue(stations.mapIndexed { i, station ->
            MediaSessionCompat.QueueItem(stationDescription(station), i.toLong())
        })
    }

    /** Fetches the station's real logo off the poll loop and swaps it in if still on that station. */
    private fun lookUpLogo(station: Station) {
        if (station.name.isBlank()) return
        scope.launch {
            val logo = logos.find(station.name) ?: return@launch
            synchronized(this@RadioMediaService) {
                val current = shownStation ?: return@launch
                if (current.key != station.key) return@launch
                webLogo = logo
                lastArt = null
                publishMetadata(current, null)
            }
        }
    }

    private var lastArt: Bitmap? = null

    private fun publishMetadata(station: Station, logo: Bitmap?) {
        val web = webLogo
        val art = when {
            web != null && web.width == web.height -> web
            web != null -> StationArtwork.forStation(station, web)
            logo != null || station.key != shownKey || lastArt == null -> StationArtwork.forStation(station, logo)
            else -> lastArt
        }
        lastArt = art
        val title = station.name.ifBlank { station.bandLabel }
        val subtitle = station.nowPlaying.ifBlank { station.bandLabel }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, station.key)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, station.bandLabel)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                .build(),
        )
    }

    private fun publishState(state: Int) {
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM,
            )
            .setActiveQueueItemId(queueKeys.indexOf(shownStation?.key).toLong().takeIf { it >= 0 } ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong())
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
        if (state == PlaybackStateCompat.STATE_ERROR) {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, getString(if (client.blockedByVpn) R.string.vpn_blocks else R.string.not_connected))
        }
        session.setPlaybackState(builder.build())
    }

    companion object {
        private const val ROOT = "root"
        private const val FAVOURITES = "favourites"
        private const val DAB = "dab"
        private const val FM = "fm"
        private const val POLL_MS = 1_500L
        private const val POLL_UNREACHABLE_MS = 5_000L
        private const val SETTLE_MS = 400L
        private const val LOGO_ATTEMPTS = 6

        // Android Auto content-style hints (androidx.media.utils.MediaConstants).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
    }
}
