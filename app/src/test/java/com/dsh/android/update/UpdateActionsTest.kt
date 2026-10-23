package com.dsh.android.update

import org.junit.Assert.*
import org.junit.Test

class UpdateActionsTest {
    @Test fun permissionDenialAndDownloadErrorsStillAllowAnUpdateRetry() {
        for (status in listOf("idle", "error")) {
            val actions = UpdateActions.app(hasOffer = true, status = status, hasVerifiedApk = false)
            assertTrue(actions.canUpdate)
            assertFalse(actions.canInstall)
        }
    }

    @Test fun cancellingTheInstallerLeavesTheVerifiedApkInstallable() {
        for (status in listOf("ready", "error", "idle")) {
            val actions = UpdateActions.app(hasOffer = true, status = status, hasVerifiedApk = true)
            assertTrue(actions.canInstall)
            assertFalse(actions.canUpdate)
        }
    }

    @Test fun anActiveDownloadOrAnOldReleaseCannotBeStartedAgain() {
        assertFalse(UpdateActions.app(true, "downloading", false).canUpdate)
        assertFalse(UpdateActions.app(true, "downloading", true).canInstall)
        assertFalse(UpdateActions.app(false, "error", false).canUpdate)
        assertFalse(UpdateActions.app(false, "ready", true).canInstall)
    }

    @Test fun harnessErrorsAllowRetryOnlyForAnEligibleIdleCandidate() {
        assertTrue(UpdateActions.harness(true, "error", blocked = false).canUpdate)
        assertFalse(UpdateActions.harness(true, "installing", blocked = false).canUpdate)
        assertFalse(UpdateActions.harness(true, "error", blocked = true).canUpdate)
        assertFalse(UpdateActions.harness(false, "error", blocked = false).canUpdate)
    }
}
