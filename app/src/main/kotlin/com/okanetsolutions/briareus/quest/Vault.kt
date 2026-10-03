package com.okanetsolutions.briareus.quest

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption at rest. One AES-256-GCM key is generated inside the Android Keystore (hardware-backed where the headset has
 * it) and never leaves it, so what it seals cannot be read off the device or restored on another one. The token, the
 * saved connection and the saved responses are all sealed with it; forgetting the connection deletes the key, which makes
 * anything left behind unreadable.
 */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** IV (12 bytes) followed by the ciphertext and its tag. [context] is bound as associated data, so a sealed value moved to another slot fails to open. */
    fun seal(plain: ByteArray, context: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray())
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Null when the value was tampered with, sealed for another slot, or sealed with a key since deleted. */
    fun open(sealed: ByteArray, context: String): ByteArray? = runCatching {
        if (sealed.size < IV_BYTES + 16) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        cipher.updateAAD(context.toByteArray())
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }.getOrNull()

    fun putString(name: String, value: String?) {
        prefs.edit {
            if (value == null) remove(name) else putString(name, Base64.encodeToString(seal(value.toByteArray(), "pref:$name"), Base64.NO_WRAP))
        }
    }

    fun getString(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        return open(bytes, "pref:$name")?.toString(Charsets.UTF_8)
    }

    /** Removes every sealed value and the key itself. */
    fun destroy() {
        prefs.edit { clear() }
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "briareus.vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
