package com.neko7ina.syncclipboard.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.neko7ina.syncclipboard.data.ServerConfig
import com.neko7ina.syncclipboard.sync.SyncException
import com.neko7ina.syncclipboard.sync.SyncFailureKind
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.io.IOException

/** 权限属于主体应用 UID，HTTP、重定向与 SignalR 使用同一检查。 */
internal object LocalNetworkAccess {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    const val DENIED_MESSAGE = "未允许访问局域网，请在应用权限设置中允许「附近设备」后重试"

    fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 在 IO 线程调用。域名也可能指向局域网，不能只检查 URL 是否为私有 IP。 */
    fun needsPermission(context: Context, config: ServerConfig): Boolean =
        needsPermissionForHost(context, config.normalizedUrl.toHttpUrl().host)

    private fun needsPermissionForHost(context: Context, host: String): Boolean {
        if (isGranted(context)) return false
        if (host.endsWith(".local", ignoreCase = true)) return true
        return runCatching { Dns.SYSTEM.lookup(host).any(::isLocalAddress) }.getOrDefault(false)
    }

    fun configure(builder: OkHttpClient.Builder, context: Context) {
        val appContext = context.applicationContext
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!isGranted(appContext) && hostname.endsWith(".local", ignoreCase = true)) {
                    throw LocalNetworkPermissionException()
                }
                return Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (!isGranted(appContext) && addresses.any(::isLocalAddress)) {
                        throw LocalNetworkPermissionException()
                    }
                }
            }
        })
        // IP 字面量可能跳过 DNS，连接前还要检查一次。公网请求不受未授权影响。
        builder.addInterceptor { chain ->
            if (needsPermissionForHost(appContext, chain.request().url.host)) {
                throw LocalNetworkPermissionException()
            }
            chain.proceed(chain.request())
        }
        builder.addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { response ->
                // 每一跳重定向都要检查，避免公网请求转到局域网 IP 时只表现为超时。
                if (response.code in setOf(300, 301, 302, 303, 307, 308)) {
                    val target = response.header("Location")?.let(response.request.url::resolve)
                    if (target != null && needsPermissionForHost(appContext, target.host)) {
                        response.close()
                        throw LocalNetworkPermissionException()
                    }
                }
            }
        }
    }

    fun denied(): SyncException = SyncException(DENIED_MESSAGE, failureKind = SyncFailureKind.PERMISSION)

    internal fun isLocalAddress(address: InetAddress): Boolean =
        !address.isLoopbackAddress && (
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
            )
}

internal class LocalNetworkPermissionException : IOException(LocalNetworkAccess.DENIED_MESSAGE)
