package com.huaxianyan.syncclipboard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SyncDirection {
    UPLOAD,
    DOWNLOAD,
}

data class LastSync(
    val timestampMillis: Long,
    val direction: SyncDirection,
)

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadServerProfiles(): ServerProfiles {
        preferences.getString(KEY_SERVER_PROFILES, null)?.let { stored ->
            return runCatching { decodeProfiles(stored) }.getOrDefault(ServerProfiles(emptyList(), null))
        }

        val legacyUrl = preferences.getString(KEY_URL, null)?.trim().orEmpty()
        if (legacyUrl.isEmpty()) return ServerProfiles(emptyList(), null)

        val migrated = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = "",
            url = legacyUrl,
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
            trustInsecureCertificate = preferences.getBoolean(KEY_TRUST_INSECURE, false),
        )
        return ServerProfiles(listOf(migrated), migrated.id).also(::persistMigratedProfiles)
    }

    fun loadServer(): ServerConfig? = loadServerProfiles().activeServer

    @Synchronized
    fun saveServer(config: ServerConfig): ServerProfiles {
        config.validate()
        val current = loadServerProfiles()
        val servers = current.servers.toMutableList()
        val existingIndex = servers.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            servers[existingIndex] = config
        } else {
            servers += config
        }
        return ServerProfiles(servers, config.id).also(::persistProfiles)
    }

    @Synchronized
    fun selectServer(serverId: String): ServerProfiles {
        val current = loadServerProfiles()
        require(current.servers.any { it.id == serverId }) { "服务器方案不存在" }
        return current.copy(activeServerId = serverId).also(::persistProfiles)
    }

    fun loadLastSync(): LastSync? {
        val timestamp = preferences.getLong(KEY_LAST_SYNC_TIME, 0L)
        if (timestamp <= 0L) return null
        val direction = preferences.getString(KEY_LAST_SYNC_DIRECTION, null)
            ?.let { runCatching { SyncDirection.valueOf(it) }.getOrNull() }
            ?: return null
        return LastSync(timestamp, direction)
    }

    fun recordSuccessfulSync(direction: SyncDirection) {
        preferences.edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_DIRECTION, direction.name)
            .apply()
    }

    private fun persistMigratedProfiles(profiles: ServerProfiles) {
        preferences.edit()
            .putString(KEY_SERVER_PROFILES, encodeProfiles(profiles))
            .remove(KEY_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_TRUST_INSECURE)
            .apply()
    }

    private fun persistProfiles(profiles: ServerProfiles) {
        check(
            preferences.edit()
                .putString(KEY_SERVER_PROFILES, encodeProfiles(profiles))
                .remove(KEY_URL)
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .remove(KEY_TRUST_INSECURE)
                .commit(),
        ) { "保存服务器配置失败" }
    }

    private fun encodeProfiles(profiles: ServerProfiles): String = JSONObject().apply {
        put(KEY_ACTIVE_SERVER_ID, profiles.activeServerId ?: JSONObject.NULL)
        put(KEY_SERVERS, JSONArray().apply {
            profiles.servers.forEach { server ->
                put(JSONObject().apply {
                    put(KEY_ID, server.id)
                    put(KEY_NAME, server.name)
                    put(KEY_URL_JSON, server.normalizedUrl)
                    put(KEY_USERNAME_JSON, server.username)
                    put(KEY_PASSWORD_JSON, server.password)
                    put(KEY_TRUST_INSECURE_JSON, server.trustInsecureCertificate)
                })
            }
        })
    }.toString()

    private fun decodeProfiles(raw: String): ServerProfiles {
        val root = JSONObject(raw)
        val serverArray = root.getJSONArray(KEY_SERVERS)
        val servers = buildList {
            for (index in 0 until serverArray.length()) {
                val item = serverArray.getJSONObject(index)
                add(
                    ServerConfig(
                        id = item.getString(KEY_ID),
                        name = item.optString(KEY_NAME),
                        url = item.getString(KEY_URL_JSON),
                        username = item.optString(KEY_USERNAME_JSON),
                        password = item.optString(KEY_PASSWORD_JSON),
                        trustInsecureCertificate = item.optBoolean(KEY_TRUST_INSECURE_JSON),
                    ),
                )
            }
        }
        val storedActiveId = root.optString(KEY_ACTIVE_SERVER_ID).takeIf(String::isNotEmpty)
        val activeId = storedActiveId?.takeIf { id -> servers.any { it.id == id } }
            ?: servers.firstOrNull()?.id
        return ServerProfiles(servers, activeId)
    }

    private companion object {
        const val PREFERENCES_NAME = "sync_clipboard_settings"
        const val KEY_SERVER_PROFILES = "server_profiles"
        const val KEY_ACTIVE_SERVER_ID = "activeServerId"
        const val KEY_SERVERS = "servers"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_URL_JSON = "url"
        const val KEY_USERNAME_JSON = "username"
        const val KEY_PASSWORD_JSON = "password"
        const val KEY_TRUST_INSECURE_JSON = "trustInsecureCertificate"

        const val KEY_URL = "server_url"
        const val KEY_USERNAME = "server_username"
        const val KEY_PASSWORD = "server_password"
        const val KEY_TRUST_INSECURE = "trust_insecure_certificate"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
        const val KEY_LAST_SYNC_DIRECTION = "last_sync_direction"
    }
}
