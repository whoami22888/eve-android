package com.eve.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted single-operator bridge settings; no API credentials in build resources. */
class CloudAgentSettingsStore(context: Context) {
    data class Settings(val url: String, val token: String)
    private val preferences = context.applicationContext.getSharedPreferences("cloud_agent", Context.MODE_PRIVATE)
    private val alias = "eve_cloud_agent_aes_1"

    @Synchronized fun save(url: String, token: String) {
        val endpoint = url.trim().trimEnd('/')
        require(endpoint.startsWith("https://") && endpoint.length > 8) { "HTTPS URL is required" }
        require(token.length >= 24) { "Token must be at least 24 characters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject().put("url", endpoint).put("token", token).toString()
        val ciphertext = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("settings", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun load(): Settings? {
        val encoded = preferences.getString("settings", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val decrypted = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            val json = JSONObject(String(decrypted, Charsets.UTF_8))
            Settings(json.getString("url"), json.getString("token"))
        } catch (_: Exception) { null }
    }

    @Synchronized fun clear() {
        preferences.edit().remove("settings").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
