package com.neko7ina.syncclipboard.sync

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
    fun `unrecognized failure remains unknown`() {
        assertEquals(
            SyncFailureKind.UNKNOWN,
            IllegalStateException("unexpected").toSyncFailureKind(),
        )
    }
}
