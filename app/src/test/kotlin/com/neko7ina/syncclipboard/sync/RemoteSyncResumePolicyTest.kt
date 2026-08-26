package com.neko7ina.syncclipboard.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSyncResumePolicyTest {
    @Test
    fun `恢复自动同步时建立一次基线而技术重连继续接收`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )

        assertTrue(policy.shouldEstablishBaseline(false, "old-hash"))
        policy.markBaselineEstablished()
        assertFalse(policy.shouldEstablishBaseline(false, "baseline-hash"))

        policy.onAutomaticConditionLost(receivePausedRemoteChanges = false)
        assertTrue(policy.shouldEstablishBaseline(false, "baseline-hash"))
    }

    @Test
    fun `关闭暂停期间接收或关闭远端同步后要求新基线`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = true,
            remoteSyncEnabled = true,
        )

        policy.onSettingsChanged(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )
        assertTrue(policy.shouldEstablishBaseline(false, "existing-hash"))
        policy.markBaselineEstablished()

        policy.onSettingsChanged(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = false,
        )
        assertTrue(policy.shouldEstablishBaseline(false, "existing-hash"))
    }
}
