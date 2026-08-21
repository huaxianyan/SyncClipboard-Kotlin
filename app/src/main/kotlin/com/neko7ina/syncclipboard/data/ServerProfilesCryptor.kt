package com.neko7ina.syncclipboard.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ServerProfilesCryptor(
    private val keyProvider: () -> SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            updateAAD(ASSOCIATED_DATA)
        }
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
            updateAAD(ASSOCIATED_DATA)
        }
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        const val FORMAT_VERSION = "v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        val ASSOCIATED_DATA = "SyncClipboard server profiles v1".toByteArray(Charsets.UTF_8)
    }
}

internal object AndroidServerProfilesKey {
    private const val KEY_ALIAS = "syncclipboard_server_profiles_v1"

    fun getOrCreate(): SecretKey {
        loadKey()?.let { return it }
        return runCatching { generateKey() }.getOrElse { generationError ->
            loadKey() ?: throw generationError
        }
    }

    private fun loadKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun generateKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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
