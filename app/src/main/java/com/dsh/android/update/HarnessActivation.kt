package com.dsh.android.update

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Pending candidates are validated without repairing user config and stopped before any slot is replaced. */
class HarnessActivation(private val slots: EngineSlots, private val stopCandidate: () -> Unit) {
    enum class Result { NOT_PENDING, CONFIRMED, ROLLED_BACK, ROLLBACK_FAILED }

    class InvalidCandidate : IOException("Updated harness configuration could not be loaded")

    fun validateConfiguration(exitCode: Int?, hasRequiredEntries: Boolean, hasSkippedBundles: Boolean) {
        if (slots.pending() != null && (exitCode != 0 || !hasRequiredEntries || hasSkippedBundles)) throw InvalidCandidate()
    }

    fun failed(): Result {
        if (slots.pending() == null) return Result.NOT_PENDING
        return try {
            stopCandidate()
            if (slots.rollback()) Result.ROLLED_BACK else Result.ROLLBACK_FAILED
        } catch (_: Exception) {
            Result.ROLLBACK_FAILED
        }
    }

    fun ready(gatewayHealthy: Boolean, processAlive: Boolean = true): Result {
        if (slots.pending() == null) return Result.NOT_PENDING
        if (!gatewayHealthy || !processAlive) return failed()
        slots.confirm()
        return Result.CONFIRMED
    }

    companion object {
        /** A slot may only move once every process using its files has actually exited. */
        fun stopAndReap(process: Process) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(3, TimeUnit.SECONDS)) throw IOException("Engine process did not stop")
            }
        }
    }
}
