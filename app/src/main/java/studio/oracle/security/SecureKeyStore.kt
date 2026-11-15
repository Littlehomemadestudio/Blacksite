package studio.oracle.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's Gemini API key as AES-GCM ciphertext. The AES key is non-exportable
 * and held by Android Keystore; plaintext is only returned to the caller when needed.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_credentials", Context.MODE_PRIVATE)
    private val alias = "oracle.gemini.api.aes"
    private val tavilyAlias = "oracle.tavily.api.aes"

    fun hasKey(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun hasTavilyKey(): Boolean = prefs.contains(TAVILY_KEY_CIPHERTEXT)

    fun saveTavilyKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Search API key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(tavilyAlias))
        val payload = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(TAVILY_KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Could not persist the encrypted search credential."
        }
    }

    fun readTavilyKey(): String? {
        val encoded = prefs.getString(TAVILY_KEY_CIPHERTEXT, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Stored search credential is corrupted." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingSecretKey(tavilyAlias), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun clearTavilyKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(tavilyAlias)
        check(!keyStore.containsAlias(tavilyAlias)) { "Could not remove the Android Keystore search key." }
        check(prefs.edit().remove(TAVILY_KEY_CIPHERTEXT).commit()) {
            "Could not remove the encrypted search credential."
        }
    }

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        check(prefs.edit().putString(KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Could not persist the encrypted API key."
        }
    }

    fun read(): String? {
        val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Stored credential is corrupted." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun clear() {
        // Delete the key first. If Keystore refuses, keep the ciphertext so the UI can
        // report failure and the user can retry instead of claiming a partial removal.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias)
        check(!keyStore.containsAlias(alias)) { "Could not remove the Android Keystore key." }
        check(prefs.edit().remove(KEY_CIPHERTEXT).commit()) {
            "Could not remove the encrypted API key."
        }
    }

    private fun getExistingSecretKey(): SecretKey = getExistingSecretKey(alias)

    private fun getExistingSecretKey(targetAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(targetAlias, null) as? SecretKey
            ?: throw IllegalStateException("The encryption key is missing. Remove the stored credential and reconnect.")
    }

    private fun getOrCreateSecretKey(): SecretKey = getOrCreateSecretKey(alias)

    private fun getOrCreateSecretKey(targetAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(targetAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                targetAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_CIPHERTEXT = "gemini_api_key_aes_gcm"
        const val TAVILY_KEY_CIPHERTEXT = "tavily_api_key_aes_gcm"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
