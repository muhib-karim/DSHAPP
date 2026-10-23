package com.dsh.android.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EngineLifecycleTest {
    @Test fun replacementWaitsForBootPreparationThenStopsBeforeSwapping() = runBlocking {
        val lifecycle = EngineLifecycle()
        val finishPreparation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val boot = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.exclusive {
                events += "preparing old"
                finishPreparation.await()
                events += "launched old"
            }
        }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.exclusive {
                events += "stopped old"
                events += "promoted candidate"
            }
        }
        try {
            assertFalse("replacement must wait while preparation owns the engine", replacement.isCompleted)
            assertEquals(listOf("preparing old"), events)
        } finally {
            finishPreparation.complete(Unit)
            boot.join()
            replacement.join()
        }
        assertEquals(listOf("preparing old", "launched old", "stopped old", "promoted candidate"), events)
    }

    @Test fun preparationFailureReleasesTheEngineForRecovery() = runBlocking {
        val lifecycle = EngineLifecycle()
        runCatching { lifecycle.exclusive { throw IllegalStateException("preparation failed") } }
        assertEquals("recovered", lifecycle.exclusive { "recovered" })
    }
}
