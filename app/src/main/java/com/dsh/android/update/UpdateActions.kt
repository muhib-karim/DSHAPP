package com.dsh.android.update

/**
 * Eligibility survives transient errors and an installer cancellation; progress only disables concurrent work.
 * [canCancel] is set only while the app APK is actually downloading, so the page never offers a no-op cancel.
 */
data class UpdateActions(
    val canUpdate: Boolean,
    val canInstall: Boolean = false,
    val canCancel: Boolean = false,
) {
    companion object {
        fun harness(hasOffer: Boolean, status: String, blocked: Boolean) =
            UpdateActions(hasOffer && status != "installing" && !blocked)

        fun app(hasOffer: Boolean, status: String, hasVerifiedApk: Boolean): UpdateActions {
            val downloading = status == "downloading"
            val available = hasOffer && !downloading
            return UpdateActions(available && !hasVerifiedApk, available && hasVerifiedApk, downloading)
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
