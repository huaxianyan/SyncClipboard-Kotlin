package com.neko7ina.syncclipboard.sync

import com.neko7ina.syncclipboard.net.LocalNetworkAccess
import com.neko7ina.syncclipboard.net.LocalNetworkPermissionException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureKindTest {
    @Test
    fun `explicit failure kind survives exception wrapping`() {
        val error = RuntimeException(
            SyncException(
                "保存失败",
                IOException("disk full"),
                SyncFailureKind.STORAGE,
            ),
        )

        assertEquals(SyncFailureKind.STORAGE, error.toSyncFailureKind())
    }

    @Test
    fun `ssl failure is distinct from other io failures`() {
        assertEquals(
            SyncFailureKind.TLS,
            RuntimeException(SSLHandshakeException("certificate rejected")).toSyncFailureKind(),
        )
        assertEquals(
            SyncFailureKind.NETWORK,
            RuntimeException(IOException("connection lost")).toSyncFailureKind(),
        )
    }

    @Test
    fun `wrapped sync failure exposes only its user message`() {
        val error = RuntimeException(
            "internal wrapper",
            SyncException("请检查服务器设置后重试", IllegalStateException("internal detail")),
        )

        assertEquals(
            "请检查服务器设置后重试",
            error.toSyncUserMessage("操作未完成，请稍后重试"),
        )
        assertEquals(
            "操作未完成，请稍后重试",
            IllegalStateException("internal detail").toSyncUserMessage("操作未完成，请稍后重试"),
        )
    }

    @Test
    fun `local network permission failure wins over generic io failure`() {
        val error = RuntimeException("request failed", LocalNetworkPermissionException())

        assertEquals(SyncFailureKind.PERMISSION, error.toSyncFailureKind())
        // 没有 SyncException 包装时只能退回兜底文案，不能把内部细节暴露给用户。
        assertEquals("操作未完成，请稍后重试", error.toSyncUserMessage("操作未完成，请稍后重试"))
        assertEquals(
            LocalNetworkAccess.DENIED_MESSAGE,
            LocalNetworkAccess.denied().toSyncUserMessage("操作未完成，请稍后重试"),
        )
    }

    @Test
    fun `unrecognized failure remains unknown`() {
        assertEquals(
            SyncFailureKind.UNKNOWN,
            IllegalStateException("unexpected").toSyncFailureKind(),
        )
    }
}
