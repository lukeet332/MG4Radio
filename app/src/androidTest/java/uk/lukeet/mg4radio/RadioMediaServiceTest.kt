package uk.lukeet.mg4radio

import android.content.ComponentName
import android.graphics.Bitmap
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives the service the way Android Auto does; needs the fake bridge (-PradioHost). */
@RunWith(AndroidJUnit4::class)
class RadioMediaServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var browser: MediaBrowserCompat
    private lateinit var controller: MediaControllerCompat

    @Test
    fun behavesLikeAnAndroidAutoMediaApp() {
        connect()
        // One top-level list: every DAB station, then the FM and favourites folders.
        assertEquals(listOf("dab"), children("root").map { it.mediaId })
        val dab = children("dab")
        assertEquals(listOf("fm", "favourites"), dab.takeLast(2).map { it.mediaId })
        assertEquals(3, dab.size - 2)
        assertEquals(listOf("Absolute Radio", "BBC Radio 2", "Radio X"), children("favourites").map { it.description.title.toString() })
        // FM is ordered by recent play, so only its contents are fixed.
        assertEquals(setOf("88.1 FM", "Radio X"), children("fm").map { it.description.title.toString() }.toSet())

        val controls = controller.transportControls
        controls.playFromMediaId("dab:3:49761:12289:0", null)
        awaitTitle("Absolute Radio")
        awaitState(PlaybackStateCompat.STATE_PLAYING)
        assertEquals("Oasis - Wonderwall", meta().getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        awaitArt("absolute")

        controls.skipToNext(); awaitTitle("Heart UK"); awaitArt("heart")
        controls.skipToNext(); awaitTitle("BBC Radio 2"); awaitArt("bbc2")
        assertEquals("DAB", meta().getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        controls.skipToPrevious(); awaitTitle("Heart UK")

        controls.pause(); awaitState(PlaybackStateCompat.STATE_PAUSED)
        controls.play(); awaitState(PlaybackStateCompat.STATE_PLAYING)

        controls.playFromMediaId("2:97600", null); awaitTitle("Radio X"); awaitArt("radiox")
        assertEquals("97.6 FM", meta().getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        controls.playFromMediaId("2:88100", null); awaitTitle("88.1 FM"); awaitArt("fm881")
    }

    private fun connect() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser = MediaBrowserCompat(context, ComponentName(context, RadioMediaService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        controller = MediaControllerCompat(context, browser.sessionToken); latch.countDown()
                    }
                }, null)
            browser.connect()
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "browser did not connect" }
    }

    private fun children(parent: String): List<MediaBrowserCompat.MediaItem> {
        var items: List<MediaBrowserCompat.MediaItem> = emptyList()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser.subscribe(parent, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                    items = children; browser.unsubscribe(parent); latch.countDown()
                }
            })
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "no children for $parent" }
        return items
    }

    private fun meta() = controller.metadata

    private fun awaitTitle(title: String) = waitFor("title $title") { meta()?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) == title }

    private fun awaitState(state: Int) = waitFor("state $state") { controller.playbackState?.state == state }

    /** Saves the cover art so it can be pulled and looked at. */
    private fun awaitArt(name: String) {
        Thread.sleep(3_500) // let the DAB logo retry land
        val art = meta().getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        assertNotNull(art)
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for $what (title=${meta()?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)} state=${controller.playbackState?.state})")
    }
}
