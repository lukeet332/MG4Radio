package uk.lukeet.mg4radio

/**
 * Whether the car's radio counts as lost. A single failed or empty poll (DAB re-syncing, one slow
 * reply) keeps the last station on screen; only a run of them shows "not connected".
 */
class Reachability {
    private var failures = 0

    /** Records one poll; returns true once the car should be shown as lost. */
    fun onPoll(ok: Boolean): Boolean {
        failures = if (ok) 0 else failures + 1
        return failures >= FAILURES_BEFORE_LOST
    }

    companion object {
        const val FAILURES_BEFORE_LOST = 3
    }
}
