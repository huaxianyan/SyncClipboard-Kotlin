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

        assertTrue(policy.shouldEstablishBaseline(false, "old-hash", canJudgeByContentTime = false))
        assertTrue(policy.markBaselineEstablished(policy.baselineGeneration()))
        assertFalse(policy.shouldEstablishBaseline(false, "baseline-hash", canJudgeByContentTime = false))

        policy.onAutomaticConditionLost(receivePausedRemoteChanges = false)
        assertTrue(policy.shouldEstablishBaseline(false, "baseline-hash", canJudgeByContentTime = false))
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
        assertTrue(policy.shouldEstablishBaseline(false, "existing-hash", canJudgeByContentTime = false))
        assertTrue(policy.markBaselineEstablished(policy.baselineGeneration()))

        policy.onSettingsChanged(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = false,
        )
        assertTrue(policy.shouldEstablishBaseline(false, "existing-hash", canJudgeByContentTime = false))
    }

    @Test
    fun `扩展掉线属于自动条件丢失并强制重建基线`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )
        assertTrue(policy.markBaselineEstablished(policy.baselineGeneration()))
        assertFalse(policy.shouldEstablishBaseline(false, "hash-before-disconnect", canJudgeByContentTime = false))

        // 扩展 Binder 掉线导致循环停摆，之后云端可能已有新内容。
        policy.onAutomaticConditionLost(receivePausedRemoteChanges = false)
        assertTrue(policy.shouldEstablishBaseline(false, "hash-before-disconnect", canJudgeByContentTime = false))
    }

    @Test
    fun `取远端内容期间发生新的暂停时保留基线要求`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )

        assertTrue(policy.shouldEstablishBaseline(false, "hash-before", canJudgeByContentTime = false))
        val generation = policy.baselineGeneration()

        // 取远端内容是阻塞请求，期间设备锁屏，新的暂停窗口开始。
        policy.onAutomaticConditionLost(receivePausedRemoteChanges = false)

        // 这次陈旧的基线完成不得清掉新窗口刚置上的标志。
        assertFalse(policy.markBaselineEstablished(generation))
        assertTrue(policy.shouldEstablishBaseline(false, "hash-after", canJudgeByContentTime = false))

        // 重新记录同步点之后标志才会被清掉。
        assertTrue(policy.markBaselineEstablished(policy.baselineGeneration()))
        assertFalse(policy.shouldEstablishBaseline(false, "hash-after", canJudgeByContentTime = false))
    }

    @Test
    fun `暂停期间接收开启时不要求基线`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = true,
            remoteSyncEnabled = true,
        )

        assertFalse(policy.shouldEstablishBaseline(true, null, canJudgeByContentTime = false))
        policy.onAutomaticConditionLost(receivePausedRemoteChanges = true)
        assertFalse(policy.shouldEstablishBaseline(true, null, canJudgeByContentTime = false))
    }

    @Test
    fun `本地没有远端哈希时始终要求基线`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )
        assertTrue(policy.markBaselineEstablished(policy.baselineGeneration()))

        // 哈希丢失（例如服务器方案变更重置）时必须重新记录同步点。
        assertTrue(policy.shouldEstablishBaseline(false, null, canJudgeByContentTime = false))
    }

    @Test
    fun `内容时间可用时基线整体让位`() {
        val policy = RemoteSyncResumePolicy()
        policy.initialize(
            receivePausedRemoteChanges = false,
            remoteSyncEnabled = true,
        )

        // 条件丢失已经把标志置上，但只要内容时间可用就不再走基线：
        // 这条路径不再依赖存活条件是否被完整上报。
        policy.onAutomaticConditionLost(receivePausedRemoteChanges = false)
        assertFalse(policy.shouldEstablishBaseline(false, "hash", canJudgeByContentTime = true))

        // 哈希丢失（例如首次运行、服务器方案变更）同样交给内容时间判定。
        assertFalse(policy.shouldEstablishBaseline(false, null, canJudgeByContentTime = true))
    }
}
