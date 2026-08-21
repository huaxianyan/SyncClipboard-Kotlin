package com.neko7ina.syncclipboard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

class SettingsRepository(
    context: Context,
    reloadForAnotherProcess: Boolean = false,
) {
    @Suppress("DEPRECATION")
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        if (reloadForAnotherProcess) Context.MODE_MULTI_PROCESS else Context.MODE_PRIVATE,
    )
    private val profilesCryptor = ServerProfilesCryptor(AndroidServerProfilesKey::getOrCreate)

    @Volatile
    private var cachedServerProfiles: ServerProfilesLoadResult? = null

    @Synchronized
    fun loadServerProfilesResult(): ServerProfilesLoadResult {
        cachedServerProfiles?.let { return it }
        val loaded = when {
            preferences.contains(KEY_ENCRYPTED_SERVER_PROFILES) -> loadEncryptedProfiles()
            preferences.contains(KEY_SERVER_PROFILES) -> migratePlaintextProfiles(
                preferences.getString(KEY_SERVER_PROFILES, null).orEmpty(),
            )
            else -> migrateLegacyServer()
        }
        cachedServerProfiles = loaded
        return loaded
    }

    fun loadServerProfiles(): ServerProfiles = loadServerProfilesResult().profiles

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
        require(current.servers.any { it.id == serverId }) { "服务器方案不存在，请刷新后重试" }
        return current.copy(activeServerId = serverId).also(::persistProfiles)
    }

    @Synchronized
    fun deleteServer(serverId: String): ServerProfiles = loadServerProfiles()
        .withoutServer(serverId)
        .also(::persistProfiles)

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

    fun loadAdvancedSyncSettings(): AdvancedSyncSettings = AdvancedSyncSettings(
        enabled = preferences.getBoolean(KEY_ADVANCED_SYNC_ENABLED, false),
        wifiOnly = preferences.getBoolean(KEY_ADVANCED_WIFI_ONLY, false),
        uploadText = preferences.getBoolean(KEY_ADVANCED_UPLOAD_TEXT, true),
        downloadText = preferences.getBoolean(KEY_ADVANCED_DOWNLOAD_TEXT, true),
        downloadImage = preferences.getBoolean(KEY_ADVANCED_DOWNLOAD_IMAGE, false),
        downloadFile = preferences.getBoolean(KEY_ADVANCED_DOWNLOAD_FILE, false),
        ignoreSensitiveContent = preferences.getBoolean(KEY_IGNORE_SENSITIVE_CONTENT, true),
        imageSaveTreeUri = preferences.getString(KEY_IMAGE_SAVE_TREE_URI, null),
        fileSaveTreeUri = preferences.getString(KEY_FILE_SAVE_TREE_URI, null),
    )

    fun loadLastAutomaticRemoteHash(): String? =
        preferences.getString(KEY_LAST_AUTOMATIC_REMOTE_HASH, null)

    fun saveLastAutomaticRemoteHash(hash: String) {
        preferences.edit().putString(KEY_LAST_AUTOMATIC_REMOTE_HASH, hash).apply()
    }

    fun loadPendingAutomaticText(): String? =
        preferences.getString(KEY_PENDING_AUTOMATIC_TEXT, null)?.takeIf(String::isNotBlank)

    fun savePendingAutomaticText(text: String) {
        preferences.edit().putString(KEY_PENDING_AUTOMATIC_TEXT, text).apply()
    }

    fun clearPendingAutomaticTextIfMatches(text: String) {
        if (preferences.getString(KEY_PENDING_AUTOMATIC_TEXT, null) == text) {
            preferences.edit().remove(KEY_PENDING_AUTOMATIC_TEXT).apply()
        }
    }

    fun clearPendingAutomaticText() {
        preferences.edit().remove(KEY_PENDING_AUTOMATIC_TEXT).apply()
    }

    fun saveAdvancedSyncSettings(settings: AdvancedSyncSettings) {
        check(
            preferences.edit()
                .putBoolean(KEY_ADVANCED_SYNC_ENABLED, settings.enabled)
                .putBoolean(KEY_ADVANCED_WIFI_ONLY, settings.wifiOnly)
                .putBoolean(KEY_ADVANCED_UPLOAD_TEXT, settings.uploadText)
                .putBoolean(KEY_ADVANCED_DOWNLOAD_TEXT, settings.downloadText)
                .putBoolean(KEY_ADVANCED_DOWNLOAD_IMAGE, settings.downloadImage)
                .putBoolean(KEY_ADVANCED_DOWNLOAD_FILE, settings.downloadFile)
                .putBoolean(KEY_IGNORE_SENSITIVE_CONTENT, settings.ignoreSensitiveContent)
                .putNullableString(KEY_IMAGE_SAVE_TREE_URI, settings.imageSaveTreeUri)
                .putNullableString(KEY_FILE_SAVE_TREE_URI, settings.fileSaveTreeUri)
                .remove(KEY_POLLING_INTERVAL_SECONDS)
                .commit(),
        ) { "保存自动同步设置失败" }
    }

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

    private fun loadEncryptedProfiles(): ServerProfilesLoadResult = runCatching {
        val encrypted = preferences.getString(KEY_ENCRYPTED_SERVER_PROFILES, null)
            ?: error("Encrypted server profiles are missing")
        ServerProfilesLoadResult(decodeProfiles(profilesCryptor.decrypt(encrypted)))
    }.getOrElse {
        Log.e(TAG, "Unable to decrypt server profiles", it)
        unavailableServerProfiles()
    }

    private fun migratePlaintextProfiles(raw: String): ServerProfilesLoadResult = runCatching {
        val profiles = decodeProfiles(raw)
        persistProfiles(profiles, resetRemoteHash = false)
        ServerProfilesLoadResult(profiles)
    }.getOrElse {
        Log.e(TAG, "Unable to migrate server profiles", it)
        unavailableServerProfiles()
    }

    private fun migrateLegacyServer(): ServerProfilesLoadResult {
        val legacyUrl = preferences.getString(KEY_URL, null)?.trim().orEmpty()
        if (legacyUrl.isEmpty()) return ServerProfilesLoadResult(ServerProfiles(emptyList(), null))
        return runCatching {
            val migrated = ServerConfig(
                id = UUID.randomUUID().toString(),
                name = "",
                url = legacyUrl,
                username = preferences.getString(KEY_USERNAME, "").orEmpty(),
                password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
                trustInsecureCertificate = preferences.getBoolean(KEY_TRUST_INSECURE, false),
            )
            val profiles = ServerProfiles(listOf(migrated), migrated.id)
            persistProfiles(profiles, resetRemoteHash = false)
            ServerProfilesLoadResult(profiles)
        }.getOrElse {
            Log.e(TAG, "Unable to migrate legacy server profile", it)
            unavailableServerProfiles()
        }
    }

    private fun unavailableServerProfiles() = ServerProfilesLoadResult(
        profiles = ServerProfiles(emptyList(), null),
        credentialsUnavailable = true,
    )

    private fun persistProfiles(
        profiles: ServerProfiles,
        resetRemoteHash: Boolean = true,
    ) {
        val encrypted = runCatching { profilesCryptor.encrypt(encodeProfiles(profiles)) }
            .getOrElse {
                Log.e(TAG, "Unable to encrypt server profiles", it)
                throw IllegalStateException(
                    "无法安全保存服务器配置，请重新启动设备后重试",
                    it,
                )
            }
        val editor = preferences.edit()
            .putString(KEY_ENCRYPTED_SERVER_PROFILES, encrypted)
            .remove(KEY_SERVER_PROFILES)
            .remove(KEY_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_TRUST_INSECURE)
        if (resetRemoteHash) editor.remove(KEY_LAST_AUTOMATIC_REMOTE_HASH)
        check(editor.commit()) { "保存服务器配置失败，请检查设备存储空间后重试" }
        cachedServerProfiles = ServerProfilesLoadResult(profiles)
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
        const val TAG = "ServerProfilesStorage"
        const val PREFERENCES_NAME = "sync_clipboard_settings"
        const val KEY_ENCRYPTED_SERVER_PROFILES = "server_profiles_encrypted_v1"
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
        const val KEY_ADVANCED_SYNC_ENABLED = "advanced_sync_enabled"
        const val KEY_ADVANCED_WIFI_ONLY = "advanced_wifi_only"
        const val KEY_ADVANCED_UPLOAD_TEXT = "advanced_upload_text"
        const val KEY_ADVANCED_DOWNLOAD_TEXT = "advanced_download_text"
        const val KEY_ADVANCED_DOWNLOAD_IMAGE = "advanced_download_image"
        const val KEY_ADVANCED_DOWNLOAD_FILE = "advanced_download_file"
        const val KEY_IGNORE_SENSITIVE_CONTENT = "ignore_sensitive_content"
        const val KEY_IMAGE_SAVE_TREE_URI = "image_save_tree_uri"
        const val KEY_FILE_SAVE_TREE_URI = "file_save_tree_uri"
        const val KEY_POLLING_INTERVAL_SECONDS = "polling_interval_seconds"
        const val KEY_LAST_AUTOMATIC_REMOTE_HASH = "last_automatic_remote_hash"
        const val KEY_PENDING_AUTOMATIC_TEXT = "pending_automatic_text"
    }
}
