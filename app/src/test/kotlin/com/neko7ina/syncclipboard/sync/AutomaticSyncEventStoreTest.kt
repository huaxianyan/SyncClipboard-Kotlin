package com.neko7ina.syncclipboard.sync

import java.io.File
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSyncEventStoreTest {
    private val directory = createTempDirectory("automatic-sync-events-").toFile()
    private val eventFile = File(directory, "events")
    private var now = 1_700_000_000_000L
    private val store = AutomaticSyncEventStore(eventFile) { now }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `recent automatic sync activity remains available after reopening storage`() {
        store.record(
            AutomaticSyncEventKind.UPLOAD_SUCCEEDED,
            contentType = ClipboardType.TEXT,
        )
        now += 1_000
        store.record(
            AutomaticSyncEventKind.DOWNLOAD_FAILED,
            failure = SyncFailureKind.NETWORK,
            contentType = ClipboardType.IMAGE,
        )

        val reopened = AutomaticSyncEventStore(eventFile) { now }.read()

        assertEquals(
            listOf(
                AutomaticSyncEvent(
                    1_700_000_000_000L,
                    AutomaticSyncEventKind.UPLOAD_SUCCEEDED,
                    contentType = ClipboardType.TEXT,
                ),
                AutomaticSyncEvent(
                    1_700_000_001_000L,
                    AutomaticSyncEventKind.DOWNLOAD_FAILED,
                    SyncFailureKind.NETWORK,
                    ClipboardType.IMAGE,
                ),
            ),
            reopened,
        )
    }

    @Test
    fun `automatic sync activity keeps the latest one hundred events for seven days`() {
        repeat(105) {
            store.record(AutomaticSyncEventKind.UPLOAD_SUCCEEDED)
            now += 1_000
        }
        assertEquals(100, store.read().size)

        now += 8L * 24 * 60 * 60 * 1_000
        store.record(AutomaticSyncEventKind.REALTIME_CONNECTED)

        assertEquals(
            listOf(AutomaticSyncEvent(now, AutomaticSyncEventKind.REALTIME_CONNECTED)),
            store.read(),
        )
    }

    @Test
    fun `simultaneous automatic sync results are retained`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            executor.invokeAll(
                List(20) {
                    java.util.concurrent.Callable {
                        store.record(AutomaticSyncEventKind.UPLOAD_SUCCEEDED)
                    }
                },
            ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(20, store.read().size)
    }

    @Test
    fun `repeated connection failures produce one record until connection recovers`() {
        store.record(AutomaticSyncEventKind.REALTIME_FAILED, SyncFailureKind.NETWORK)
        now += 5_000
        store.record(AutomaticSyncEventKind.REALTIME_FAILED, SyncFailureKind.NETWORK)
        now += 5_000
        store.record(AutomaticSyncEventKind.REALTIME_CONNECTED)
        now += 5_000
        store.record(AutomaticSyncEventKind.REALTIME_FAILED, SyncFailureKind.NETWORK)

        assertEquals(
            listOf(
                AutomaticSyncEvent(
                    1_700_000_000_000L,
                    AutomaticSyncEventKind.REALTIME_FAILED,
                    SyncFailureKind.NETWORK,
                ),
                AutomaticSyncEvent(
                    1_700_000_010_000L,
                    AutomaticSyncEventKind.REALTIME_CONNECTED,
                ),
                AutomaticSyncEvent(
                    1_700_000_015_000L,
                    AutomaticSyncEventKind.REALTIME_FAILED,
                    SyncFailureKind.NETWORK,
                ),
            ),
            store.read(),
        )
    }
}
