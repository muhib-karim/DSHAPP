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
    }
}
