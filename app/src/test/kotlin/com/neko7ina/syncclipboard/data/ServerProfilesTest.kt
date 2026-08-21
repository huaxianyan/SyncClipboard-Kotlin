package com.neko7ina.syncclipboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfilesTest {
    @Test
    fun `删除当前服务器后选择剩余方案`() {
        val first = server("first", "https://first.example/")
        val second = server("second", "https://second.example/")
        val profiles = ServerProfiles(listOf(first, second), second.id)

        val updated = profiles.withoutServer(second.id)

        assertEquals(listOf(first), updated.servers)
        assertEquals(first, updated.activeServer)
    }

    @Test
    fun `删除最后一个服务器后返回未配置状态`() {
        val only = server("only", "https://only.example/")

        val updated = ServerProfiles(listOf(only), only.id).withoutServer(only.id)

        assertEquals(emptyList<ServerConfig>(), updated.servers)
        assertNull(updated.activeServer)
    }

    private fun server(id: String, url: String) = ServerConfig(
        id = id,
        name = id,
        url = url,
        username = "user",
        password = "password",
    )
}
