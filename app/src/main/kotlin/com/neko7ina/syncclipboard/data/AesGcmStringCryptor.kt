package com.neko7ina.syncclipboard.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AesGcmStringCryptor(
    private val associatedData: ByteArray,
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider())
            updateAAD(associatedData)
        }
        val iv = cipher.iv
        require(iv.size == IV_SIZE_BYTES) { "Invalid generated encryption IV" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return listOf(
            FORMAT_VERSION,
            encoder.encodeToString(iv),
            encoder.encodeToString(ciphertext),
        ).joinToString(":")
    }

    fun decrypt(stored: String): String {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unsupported encrypted format" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        require(iv.size == IV_SIZE_BYTES) { "Invalid encryption IV" }
        val ciphertext = decoder.decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        const val FORMAT_VERSION = "v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
    }
}

internal object AndroidAesKeyStore {
    fun getOrCreate(alias: String): SecretKey {
        load(alias)?.let { return it }
        return runCatching { generate(alias) }.getOrElse { generationError ->
            load(alias) ?: throw generationError
        }
    }

    private fun load(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun generate(alias: String): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
