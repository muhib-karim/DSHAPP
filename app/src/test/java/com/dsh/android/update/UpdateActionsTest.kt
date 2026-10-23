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

    @Test fun onlyADownloadingAppCanBeCancelled() {
        assertTrue(UpdateActions.app(true, "downloading", false).canCancel)
        assertTrue(UpdateActions.app(true, "downloading", true).canCancel)
        for (status in listOf("idle", "error", "cancelled", "ready", "available", "current")) {
            assertFalse(status, UpdateActions.app(true, status, false).canCancel)
        }
        assertFalse(UpdateActions.harness(true, "downloading", false).canCancel)
    }

    @Test fun aCancelledDownloadKeepsTheRetryAction() {
        val actions = UpdateActions.app(hasOffer = true, status = "cancelled", hasVerifiedApk = false)
        assertTrue(actions.canUpdate)
        assertFalse(actions.canInstall)
        assertFalse(actions.canCancel)
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

    @Test fun manualRollbackNeedsAPreviousHarnessAndAnIdleUpdater() {
        for (status in listOf("idle", "error", "installed", "failed")) {
            assertTrue(status, UpdateActions.rollback(hasPrevious = true, status = status))
        }
        assertFalse(UpdateActions.rollback(hasPrevious = false, status = "idle"))
    }

    @Test fun manualRollbackIsNotOfferedWhileAnUpdateRuns() {
        assertFalse(UpdateActions.rollback(hasPrevious = true, status = "downloading"))
        assertFalse(UpdateActions.rollback(hasPrevious = true, status = "installing"))
    }
}
