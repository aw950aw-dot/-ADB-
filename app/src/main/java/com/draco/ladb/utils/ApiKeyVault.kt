package com.draco.ladb.utils

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

object ApiKeyVault {
    private const val STORE = "AndroidKeyStore"
    private const val PREFS = "ai_secure"

    private fun safeProvider(provider: String): String =
        provider.lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "gemini" }

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(context: Context, value: String) = save(context, value, "gemini")

    fun save(context: Context, value: String, provider: String) {
        val id = safeProvider(provider)
        val prefs = context.getSharedPreferences(PREFS, 0)
        val prefName = "key_" + id
        if (value.isBlank()) {
            prefs.edit {
                remove(prefName)
                if (id == "gemini") remove("key")
            }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key("adb_ai_api_key_" + id))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        prefs.edit {
            putString(prefName, encoded)
            if (id == "gemini") putString("key", encoded)
        }
    }

    fun read(context: Context): String = read(context, "gemini")

    fun read(context: Context, provider: String): String {
        val id = safeProvider(provider)
        val prefs = context.getSharedPreferences(PREFS, 0)
        val raw = prefs.getString("key_" + id, null)
            ?: if (id == "gemini") prefs.getString("key", null) else null
            ?: return ""
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key("adb_ai_api_key_" + id),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12))
            )
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Migrate the original Gemini key encrypted under its original keystore alias.
            if (id == "gemini") {
                try {
                    val bytes = Base64.decode(raw, Base64.NO_WRAP)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key("adb_ai_api_key"),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12))
                    )
                    val oldValue = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                    save(context, oldValue, "gemini")
                    oldValue
                } catch (_: Exception) { "" }
            } else ""
        }
    }
}