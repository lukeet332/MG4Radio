package uk.lukeet.mg4radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {

    @Test
    fun `a single failed poll does not report the car as lost`() {
        // DAB re-sync and one slow reply used to grey the whole app out.
        val r = Reachability()
        assertFalse(r.onPoll(ok = false))
        assertFalse(r.onPoll(ok = false))
    }

    @Test
    fun `reports the car as lost after consecutive failures`() {
        val r = Reachability()
        repeat(Reachability.FAILURES_BEFORE_LOST - 1) { r.onPoll(ok = false) }
        assertTrue(r.onPoll(ok = false))
    }

    @Test
    fun `a good poll resets the count`() {
        val r = Reachability()
        repeat(Reachability.FAILURES_BEFORE_LOST - 1) { r.onPoll(ok = false) }
        r.onPoll(ok = true)
        assertFalse(r.onPoll(ok = false))
    }
}
