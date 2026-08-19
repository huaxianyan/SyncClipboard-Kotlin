package com.huaxianyan.syncclipboard.data

import android.content.Context

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadServer(): ServerConfig? {
        val url = preferences.getString(KEY_URL, null)?.trim().orEmpty()
        if (url.isEmpty()) return null
        return ServerConfig(
            url = url,
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
            trustInsecureCertificate = preferences.getBoolean(KEY_TRUST_INSECURE, false),
        )
    }

    fun saveServer(config: ServerConfig) {
        config.validate()
        check(
            preferences.edit()
                .putString(KEY_URL, config.normalizedUrl)
                .putString(KEY_USERNAME, config.username)
                .putString(KEY_PASSWORD, config.password)
                .putBoolean(KEY_TRUST_INSECURE, config.trustInsecureCertificate)
                .commit(),
        ) { "保存服务器配置失败" }
    }

    private companion object {
        const val PREFERENCES_NAME = "sync_clipboard_settings"
        const val KEY_URL = "server_url"
        const val KEY_USERNAME = "server_username"
        const val KEY_PASSWORD = "server_password"
        const val KEY_TRUST_INSECURE = "trust_insecure_certificate"
    }
}
