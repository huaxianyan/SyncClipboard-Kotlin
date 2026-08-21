package com.neko7ina.syncclipboard.data

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerProfilesCryptorTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val cryptor = ServerProfilesCryptor(keyProvider = { key })

    @Test
    fun `服务器凭据加密后可以还原且密文不含明文`() {
        val plaintext = """{"username":"alice","password":"secret-value"}"""

        val encrypted = cryptor.encrypt(plaintext)

        assertEquals(plaintext, cryptor.decrypt(encrypted))
        assertFalse(encrypted.contains("alice"))
        assertFalse(encrypted.contains("secret-value"))
    }

    @Test
    fun `密文被修改后拒绝读取服务器凭据`() {
        val parts = cryptor.encrypt("server credentials").split(':').toMutableList()
        val ciphertext = Base64.getDecoder().decode(parts[2])
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        parts[2] = Base64.getEncoder().encodeToString(ciphertext)

        assertThrows(AEADBadTagException::class.java) {
            cryptor.decrypt(parts.joinToString(":"))
        }
    }
}
