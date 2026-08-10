package com.eve.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores model credentials encrypted with an Android Keystore AES key. */
class ModelProviderStore(context: Context) {
    data class Config(
        val provider: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val timeoutSeconds: Int
    )

    private val prefs = context.applicationContext.getSharedPreferences("eve_model_provider", Context.MODE_PRIVATE)
    private val keyAlias = "eve_model_provider_key"

    fun load(): Config = Config(
        provider = prefs.getString("provider", "openai") ?: "openai",
        baseUrl = prefs.getString("base_url", "") ?: "",
        model = prefs.getString("model", "") ?: "",
        apiKey = decrypt(prefs.getString("api_key", "") ?: ""),
        timeoutSeconds = prefs.getInt("timeout", 120)
    )

    fun save(config: Config) {
        prefs.edit()
            .putString("provider", config.provider.trim().lowercase())
            .putString("base_url", config.baseUrl.trim().trimEnd('/'))
            .putString("model", config.model.trim())
            .putString("api_key", encrypt(config.apiKey))
            .putInt("timeout", config.timeoutSeconds.coerceIn(10, 600))
            .apply()
    }

    fun clearApiKey() = prefs.edit().remove("api_key").apply()

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(keyAlias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            if (combined.size < 13) return ""
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
