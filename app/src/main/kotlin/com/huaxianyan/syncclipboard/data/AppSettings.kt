package com.huaxianyan.syncclipboard.data

data class ServerConfig(
    val url: String,
    val username: String,
    val password: String,
    val trustInsecureCertificate: Boolean = false,
) {
    val normalizedUrl: String
        get() = url.trim().let { if (it.endsWith('/')) it else "$it/" }

    fun validate() {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
    }
}
