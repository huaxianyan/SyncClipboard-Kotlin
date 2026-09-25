package com.neko7ina.syncclipboard.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
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
    private val context: Context,
    private val syncProcess: Boolean = false,
) {
    /**
     * 设置与服务器配置。**只由主进程写入**，`:sync` 进程只读，读时启用跨进程重新加载。
     *
     * SharedPreferences 提交时会把内存里的整张表写回文件，两个进程各写一次就必然互相覆盖
     * （`MODE_PRIVATE` 不跨进程同步，后写者的陈旧内存快照会抹掉对方刚写的键）。因此每个偏好
     * 文件只能有一个写入者：这个文件归主进程，运行时状态归 [runtimePreferences]。
     */
    @Suppress("DEPRECATION")
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        if (syncProcess) Context.MODE_MULTI_PROCESS else Context.MODE_PRIVATE,
    )

    /** 运行时状态。**只由 `:sync` 进程写入**，主进程只读（读自动同步时间用于展示）。 */
    @Suppress("DEPRECATION")
    private val runtimePreferences = context.getSharedPreferences(
        RUNTIME_PREFERENCES_NAME,
        if (syncProcess) Context.MODE_PRIVATE else Context.MODE_MULTI_PROCESS,
    )

    private val profilesCryptor = ServerProfilesCryptor(AndroidServerProfilesKey::getOrCreate)

    @Volatile
    private var cachedServerProfiles: ServerProfilesLoadResult? = null

    init {
        // 一次性清理：下面这几个键已经搬到运行时文件，留在设置文件里的旧副本会被误读。
        if (canPersistSettings) dropMigratedRuntimeKeys()
    }

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

    /**
     * 最近一次同步。手动同步由主进程记在主文件，自动同步由 `:sync` 进程记在运行时文件，
     * 两边都不写对方的文件，取值时合并，取更晚的那次。
     */
    fun loadLastSync(): LastSync? {
        val manual = readLastSync(preferences, KEY_LAST_SYNC_TIME, KEY_LAST_SYNC_DIRECTION)
        val automatic = readLastSync(runtimeSnapshot(), KEY_AUTOMATIC_SYNC_TIME, KEY_AUTOMATIC_SYNC_DIRECTION)
        return listOfNotNull(manual, automatic).maxByOrNull { it.timestampMillis }
    }

    /**
     * 运行时状态由 `:sync` 进程写入，本进程的内存快照不会自动更新。主进程读取前重新取一次实例，
     * 让 SharedPreferences 按文件时间戳重新加载；`:sync` 进程自己就是写入者，直接用现成实例。
     */
    @Suppress("DEPRECATION")
    private fun runtimeSnapshot(): SharedPreferences = if (syncProcess) {
        runtimePreferences
    } else {
        context.getSharedPreferences(RUNTIME_PREFERENCES_NAME, Context.MODE_MULTI_PROCESS)
    }

    /**
     * 是否允许写主文件。只有主进程能写：`:sync` 进程对主文件只读，否则它会用自己陈旧的内存
     * 快照整表覆盖，把主进程刚保存的设置或服务器配置抹掉。
     */
    private val canPersistSettings: Boolean get() = !syncProcess

    /** 一次性清理：这几个键已经搬到运行时文件，留在设置文件里的旧副本不该再被读到。 */
    private fun dropMigratedRuntimeKeys() {
        val staleKeys = listOf(
            KEY_LAST_AUTOMATIC_REMOTE_HASH,
            KEY_LOCAL_CLIPBOARD_HASH,
            KEY_LOCAL_CLIPBOARD_HASH_ELAPSED,
            KEY_PENDING_AUTOMATIC_TEXT,
        ).filter(preferences::contains)
        if (staleKeys.isEmpty()) return
        val editor = preferences.edit()
        staleKeys.forEach(editor::remove)
        editor.apply()
    }

    private fun readLastSync(
        source: SharedPreferences,
        timeKey: String,
        directionKey: String,
    ): LastSync? {
        val timestamp = source.getLong(timeKey, 0L)
        if (timestamp <= 0L) return null
        val direction = source.getString(directionKey, null)
            ?.let { runCatching { SyncDirection.valueOf(it) }.getOrNull() }
            ?: return null
        return LastSync(timestamp, direction)
    }

    /** 记录一次同步完成。两个进程各写自己的文件：主进程记手动同步，`:sync` 进程记自动同步。 */
    fun recordSuccessfulSync(direction: SyncDirection) {
        val target = if (syncProcess) runtimePreferences else preferences
        val timeKey = if (syncProcess) KEY_AUTOMATIC_SYNC_TIME else KEY_LAST_SYNC_TIME
        val directionKey = if (syncProcess) KEY_AUTOMATIC_SYNC_DIRECTION else KEY_LAST_SYNC_DIRECTION
        target.edit()
            .putLong(timeKey, System.currentTimeMillis())
            .putString(directionKey, direction.name)
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
        textHistoryEnabled = preferences.getBoolean(KEY_TEXT_HISTORY_ENABLED, false),
        receivePausedRemoteChanges = preferences.getBoolean(KEY_RECEIVE_PAUSED_REMOTE_CHANGES, true),
        imageSaveTreeUri = preferences.getString(KEY_IMAGE_SAVE_TREE_URI, null),
        fileSaveTreeUri = preferences.getString(KEY_FILE_SAVE_TREE_URI, null),
    )

    /**
     * 「上次同步点」。换过服务器配置后作废：主进程换配置时会更新服务器标记，而它写不到运行时
     * 文件里，所以改由本方法比对标记来判断，不依赖主进程跨文件清理。
     */
    fun loadLastAutomaticRemoteHash(): String? {
        if (runtimePreferences.getString(KEY_RUNTIME_SERVER_TOKEN, null) != currentServerToken()) return null
        return runtimePreferences.getString(KEY_LAST_AUTOMATIC_REMOTE_HASH, null)
    }

    fun saveLastAutomaticRemoteHash(hash: String) {
        runtimePreferences.edit()
            .putString(KEY_LAST_AUTOMATIC_REMOTE_HASH, hash)
            .putString(KEY_RUNTIME_SERVER_TOKEN, currentServerToken())
            .apply()
    }

    /** 服务器配置的当前标记。换方案或改配置后与运行时记录不一致，旧同步点随即作废。 */
    private fun currentServerToken(): String? = preferences.getString(KEY_SERVER_CONFIG_TOKEN, null)

    /**
     * 本机剪贴板当前内容的哈希；不可信或从未登记时为 null。
     *
     * 与 [loadLastAutomaticRemoteHash] 的区别是语义：「上次同步点」表达「这个哈希我处理过」，
     * 本方法表达「剪贴板里现在就是它」。判断远端内容要不要写进剪贴板只能靠后者。
     */
    fun loadLocalClipboardHash(): String? = LocalClipboardState.usableHash(
        nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        recordedElapsedRealtimeMillis = runtimePreferences.getLong(KEY_LOCAL_CLIPBOARD_HASH_ELAPSED, 0L),
        recordedHash = runtimePreferences.getString(KEY_LOCAL_CLIPBOARD_HASH, null),
    )

    /** 登记本机剪贴板内容。记录同时带上开机计时器，供重启后判失效。 */
    fun saveLocalClipboardHash(hash: String) {
        runtimePreferences.edit()
            .putString(KEY_LOCAL_CLIPBOARD_HASH, hash)
            .putLong(KEY_LOCAL_CLIPBOARD_HASH_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun loadPendingAutomaticText(): String? =
        runtimePreferences.getString(KEY_PENDING_AUTOMATIC_TEXT, null)?.takeIf(String::isNotBlank)

    fun savePendingAutomaticText(text: String) {
        runtimePreferences.edit().putString(KEY_PENDING_AUTOMATIC_TEXT, text).apply()
    }

    fun clearPendingAutomaticTextIfMatches(text: String) {
        if (runtimePreferences.getString(KEY_PENDING_AUTOMATIC_TEXT, null) == text) {
            runtimePreferences.edit().remove(KEY_PENDING_AUTOMATIC_TEXT).apply()
        }
    }

    fun clearPendingAutomaticText() {
        runtimePreferences.edit().remove(KEY_PENDING_AUTOMATIC_TEXT).apply()
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
                .putBoolean(KEY_TEXT_HISTORY_ENABLED, settings.textHistoryEnabled)
                .putBoolean(KEY_RECEIVE_PAUSED_REMOTE_CHANGES, settings.receivePausedRemoteChanges)
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
        // 迁移要落盘，落盘只归主进程：`:sync` 进程只把解析结果拿去用，不写文件。
        if (canPersistSettings) persistProfiles(profiles, resetRemoteHash = false)
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
            if (canPersistSettings) persistProfiles(profiles, resetRemoteHash = false)
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
        // 「上次同步点」已搬到运行时文件，主进程写不到，改为换一个服务器标记：`:sync` 进程读到时
        // 发现标记对不上，就知道它属于别的服务器，自行作废。
        if (resetRemoteHash) editor.putString(KEY_SERVER_CONFIG_TOKEN, UUID.randomUUID().toString())
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
        const val RUNTIME_PREFERENCES_NAME = "sync_clipboard_runtime"
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
        const val KEY_TEXT_HISTORY_ENABLED = "text_history_enabled"
        const val KEY_RECEIVE_PAUSED_REMOTE_CHANGES = "receive_paused_remote_changes"
        const val KEY_IMAGE_SAVE_TREE_URI = "image_save_tree_uri"
        const val KEY_FILE_SAVE_TREE_URI = "file_save_tree_uri"
        const val KEY_POLLING_INTERVAL_SECONDS = "polling_interval_seconds"
        const val KEY_LAST_AUTOMATIC_REMOTE_HASH = "last_automatic_remote_hash"
        const val KEY_LOCAL_CLIPBOARD_HASH = "local_clipboard_hash"
        const val KEY_LOCAL_CLIPBOARD_HASH_ELAPSED = "local_clipboard_hash_elapsed_millis"
        const val KEY_PENDING_AUTOMATIC_TEXT = "pending_automatic_text"
        const val KEY_SERVER_CONFIG_TOKEN = "server_config_token"
        const val KEY_RUNTIME_SERVER_TOKEN = "runtime_server_config_token"
        const val KEY_AUTOMATIC_SYNC_TIME = "automatic_sync_time"
        const val KEY_AUTOMATIC_SYNC_DIRECTION = "automatic_sync_direction"
    }
}
