package com.dsh.android.update

/** Eligibility survives transient errors and an installer cancellation; progress only disables concurrent work. */
data class UpdateActions(val canUpdate: Boolean, val canInstall: Boolean = false) {
    companion object {
        fun harness(hasOffer: Boolean, status: String, blocked: Boolean) =
            UpdateActions(hasOffer && status != "installing" && !blocked)

        fun app(hasOffer: Boolean, status: String, hasVerifiedApk: Boolean): UpdateActions {
            val available = hasOffer && status != "downloading"
            return UpdateActions(available && !hasVerifiedApk, available && hasVerifiedApk)
        }

        /**
         * A manual restore of [EngineSlots.prev] is offered only when a complete candidate exists and the harness
         * updater is neither installing nor downloading. The UI and [com.dsh.android.EngineManager] share this check
         * so a tap on a stale card cannot interrupt an update that started in between.
         */
        fun rollback(hasPrevious: Boolean, status: String) =
            hasPrevious && status != "installing" && status != "downloading"
    }
}
