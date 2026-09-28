package com.remoteviber.client.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are sealed under a non-exportable Android Keystore AES key. */
class SecureHostStorage(context: Context) {
    private val prefs = context.getSharedPreferences("viber_hosts_v2", Context.MODE_PRIVATE)
    private val alias = "remote-viber-pairing-storage-v2"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
    @Synchronized fun read(): String? {
        val encoded = prefs.getString("sealed", null) ?: return null
        require(encoded.length <= 256 * 1024) { "Saved pairing data too large" }
        val blob = Base64.getDecoder().decode(encoded)
        require(blob.size >= 28) { "Invalid sealed pairing data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        cipher.updateAAD("remote-viber-storage-v2".toByteArray(Charsets.US_ASCII))
        return String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
    }
    @Synchronized fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 160 * 1024) { "Pairing storage limit reached" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("remote-viber-storage-v2".toByteArray(Charsets.US_ASCII))
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
        check(prefs.edit().putString("sealed", encoded).commit()) { "Unable to persist pairing data" }
    }
}
