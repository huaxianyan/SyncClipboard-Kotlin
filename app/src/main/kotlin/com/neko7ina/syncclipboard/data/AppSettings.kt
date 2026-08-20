package com.neko7ina.syncclipboard.data

data class ServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val trustInsecureCertificate: Boolean = false,
) {
    val normalizedUrl: String
        get() = url.trim().let { if (it.endsWith('/')) it else "$it/" }

    val displayName: String
        get() = name.trim().ifEmpty { normalizedUrl }

    fun validate() {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
    }
}

data class ServerProfiles(
    val servers: List<ServerConfig>,
    val activeServerId: String?,
) {
    val activeServer: ServerConfig?
        get() = servers.firstOrNull { it.id == activeServerId }
}

data class AdvancedSyncSettings(
    val enabled: Boolean = false,
    val uploadText: Boolean = true,
    val downloadText: Boolean = true,
    val ignoreSensitiveContent: Boolean = true,
    val imageSaveTreeUri: String? = null,
    val fileSaveTreeUri: String? = null,
)
