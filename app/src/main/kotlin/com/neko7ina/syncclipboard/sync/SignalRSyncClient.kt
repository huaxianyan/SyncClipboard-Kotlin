package com.neko7ina.syncclipboard.sync

import androidx.annotation.Keep
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.TransportEnum
import com.neko7ina.syncclipboard.data.ServerConfig
import com.neko7ina.syncclipboard.net.HttpClientSecurity
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Credentials
import java.util.concurrent.TimeUnit

class SignalRSyncClient(
    config: ServerConfig,
    onRemoteProfileChanged: (ClipboardPayload) -> Unit,
) {
    private val closed = CompletableDeferred<Throwable?>()
    private val connection: HubConnection = HubConnectionBuilder
        .create("${config.normalizedUrl}SyncClipboardHub")
        .withTransport(TransportEnum.WEBSOCKETS)
        .withHeader(
            "Authorization",
            Credentials.basic(config.username, config.password, Charsets.UTF_8),
        )
        .setHttpClientBuilderCallback { builder ->
            HttpClientSecurity.configure(builder, config)
        }
        .build()
        .apply {
            on(
                REMOTE_PROFILE_CHANGED,
                { profile: SignalRProfile -> onRemoteProfileChanged(profile.toClipboardPayload()) },
                SignalRProfile::class.java,
            )
            onClosed { error -> closed.complete(error) }
        }

    fun start() {
        connection.start().blockingAwait()
    }

    suspend fun awaitClosed(): Throwable? = closed.await()

    fun stop() {
        runCatching {
            connection.stop()
                .timeout(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .onErrorComplete()
                .blockingAwait()
        }
    }

    private companion object {
        const val REMOTE_PROFILE_CHANGED = "RemoteProfileChanged"
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}

@Keep
private class SignalRProfile {
    var type: String = ClipboardType.TEXT.wireName
    var hash: String? = null
    var text: String = ""
    var hasData: Boolean = false
    var dataName: String? = null
    var size: Long? = null

    fun toClipboardPayload() = ClipboardPayload(
        type = ClipboardType.fromWireName(type),
        hash = hash,
        text = text,
        hasData = hasData,
        dataName = dataName,
        size = size,
    )
}
