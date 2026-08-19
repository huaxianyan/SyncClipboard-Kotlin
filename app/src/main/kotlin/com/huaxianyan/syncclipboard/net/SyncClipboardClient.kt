package com.huaxianyan.syncclipboard.net

import com.huaxianyan.syncclipboard.data.ServerConfig
import com.huaxianyan.syncclipboard.sync.ClipboardPayload
import com.huaxianyan.syncclipboard.sync.SyncException
import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SyncClipboardClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = ClientCache.get(config),
) {
    private val baseUrl = config.normalizedUrl.toHttpUrl()
    private val authorization = Credentials.basic(config.username, config.password, Charsets.UTF_8)

    fun getClipboard(): ClipboardPayload {
        val request = request("SyncClipboard.json").get().build()
        return execute(request) { body -> ClipboardPayload.fromJson(body.string()) }
    }

    fun putClipboard(payload: ClipboardPayload) {
        val body = payload.toJson().toRequestBody(JSON_MEDIA_TYPE)
        execute(request("SyncClipboard.json").put(body).build()) { Unit }
    }

    fun putFile(fileName: String, bytes: ByteArray) {
        val body = bytes.toRequestBody(BINARY_MEDIA_TYPE)
        execute(request("file", fileName).put(body).build()) { Unit }
    }

    fun getFile(fileName: String): ByteArray = execute(
        request("file", fileName).get().build(),
    ) { it.bytes() }

    fun testConnection() {
        getClipboard()
    }

    private fun request(vararg pathSegments: String): Request.Builder {
        val url = baseUrl.newBuilder().apply {
            pathSegments.forEach(::addPathSegment)
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", authorization)
    }

    private fun <T> execute(request: Request, body: (okhttp3.ResponseBody) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> Unit
                    401 -> throw SyncException("认证失败：用户名或密码错误")
                    404 -> throw SyncException("服务器文件不存在：${request.url.encodedPath}")
                    else -> throw SyncException("服务器请求失败：HTTP ${response.code}")
                }
                val responseBody = response.body ?: throw SyncException("服务器返回了空响应")
                return body(responseBody)
            }
        } catch (error: SyncException) {
            throw error
        } catch (error: IOException) {
            throw SyncException(networkErrorMessage(error), error)
        } catch (error: Exception) {
            throw SyncException("请求失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun networkErrorMessage(error: IOException): String = when {
        error is java.net.SocketTimeoutException -> "连接或传输超时"
        error is java.net.UnknownHostException -> "无法解析服务器地址"
        error is java.net.ConnectException -> "无法连接到服务器"
        else -> "网络请求失败：${error.message ?: error.javaClass.simpleName}"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

private object ClientCache {
    @Volatile
    private var cached: Pair<ServerConfig, OkHttpClient>? = null

    fun get(config: ServerConfig): OkHttpClient {
        cached?.takeIf { it.first == config }?.let { return it.second }
        return synchronized(this) {
            cached?.takeIf { it.first == config }?.second ?: build(config).also {
                cached = config to it
            }
        }
    }

    private fun build(config: ServerConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .eventListenerFactory { NetworkTimingEventListener() }
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)

        if (config.trustInsecureCertificate) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }
}

private class NetworkTimingEventListener : EventListener() {
    private val callStartedAt = now()
    private var dnsStartedAt: Long? = null
    private var dnsDuration: Long? = null
    private var connectStartedAt: Long? = null
    private var connectDuration: Long? = null
    private var tlsStartedAt: Long? = null
    private var tlsDuration: Long? = null
    private var responseHeadersAt: Long? = null

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedAt = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsDuration = elapsedSince(dnsStartedAt)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartedAt = now()
    }

    override fun secureConnectStart(call: Call) {
        tlsStartedAt = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsDuration = elapsedSince(tlsStartedAt)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        connectDuration = elapsedSince(connectStartedAt)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        responseHeadersAt = now()
    }

    override fun callEnd(call: Call) {
        log("完成", now())
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log("失败", now())
    }

    private fun log(result: String, endedAt: Long) {
        Log.i(
            TAG,
            "HTTP $result: total=${endedAt - callStartedAt}ms, " +
                "connection=${if (connectStartedAt == null) "reused" else "new"}, " +
                "dns=${dnsDuration.format()}, connect=${connectDuration.format()}, " +
                "tls=${tlsDuration.format()}, ttfb=${responseHeadersAt?.minus(callStartedAt).format()}",
        )
    }

    private fun elapsedSince(startedAt: Long?): Long? = startedAt?.let { now() - it }

    private fun Long?.format(): String = this?.let { "${it}ms" } ?: "-"

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "SyncClipboardNetwork"
    }
}
