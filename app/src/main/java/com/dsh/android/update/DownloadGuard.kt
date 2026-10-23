package com.dsh.android.update

/**
 * Generation guard for one replaceable background download (currently the app APK).
 *
 * A worker captures the generation [begin] hands out and passes it to every publish. [cancel] invalidates that
 * generation, so a download thread that is still unwinding can never overwrite the cancelled state with `ready` or
 * `error`. Cancel and a finishing publish are synchronized, so exactly one of them wins and a partial file is never
 * published as complete afterwards.
 */
class DownloadGuard {
    private var generation = 0L
    private var running = false

    /** Starts a new download and invalidates any older one. The returned generation identifies this attempt. */
    @Synchronized
    fun begin(): Long {
        generation += 1
        running = true
        return generation
    }

    @Synchronized
    fun isActive(generation: Long): Boolean = running && this.generation == generation

    /** Runs [block] (a progress update) only while [generation] is current. */
    @Synchronized
    fun publish(generation: Long, block: () -> Unit): Boolean {
        if (!isActive(generation)) return false
        block()
        return true
    }

    /** Publishes the one terminal state of [generation] and ends it. False when it was cancelled or superseded. */
    @Synchronized
    fun finish(generation: Long, block: () -> Unit): Boolean {
        if (!isActive(generation)) return false
        running = false
        block()
        return true
    }

    /** Invalidates the running download; false when there is nothing to cancel. */
    @Synchronized
    fun cancel(): Boolean {
        if (!running) return false
        running = false
        generation += 1
        return true
    }
}
