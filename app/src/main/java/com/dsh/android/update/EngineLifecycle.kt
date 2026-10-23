package com.dsh.android.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes extraction, complete boot preparation, and slot replacement for one engine manager. */
class EngineLifecycle {
    private val gate = Mutex()

    suspend fun <T> exclusive(action: suspend () -> T): T = gate.withLock { action() }
}
