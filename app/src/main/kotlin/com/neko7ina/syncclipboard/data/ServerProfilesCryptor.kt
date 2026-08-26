package com.neko7ina.syncclipboard.data

import javax.crypto.SecretKey

internal class ServerProfilesCryptor(
    keyProvider: () -> SecretKey,
) {
    private val cryptor = AesGcmStringCryptor(ASSOCIATED_DATA, keyProvider)

    fun encrypt(plaintext: String): String = cryptor.encrypt(plaintext)

    fun decrypt(stored: String): String = cryptor.decrypt(stored)

    private companion object {
        val ASSOCIATED_DATA = "SyncClipboard server profiles v1".toByteArray(Charsets.UTF_8)
    }
}

internal object AndroidServerProfilesKey {
    private const val KEY_ALIAS = "syncclipboard_server_profiles_v1"

    fun getOrCreate(): SecretKey = AndroidAesKeyStore.getOrCreate(KEY_ALIAS)
}
