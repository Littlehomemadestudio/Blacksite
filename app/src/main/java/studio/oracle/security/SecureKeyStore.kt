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
import java.security.MessageDigest

/**
 * Stores the user's Gemini API key as AES-GCM ciphertext. The AES key is non-exportable
 * and held by Android Keystore; plaintext is only returned to the caller when needed.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_credentials", Context.MODE_PRIVATE)
    private val alias = "oracle.gemini.api.aes"
    private val tavilyAlias = "oracle.tavily.api.aes"

    fun hasKey(): Boolean = prefs.contains(KEY_CIPHERTEXT) || keyLabels().isNotEmpty()

    /** Multiple Gemini credentials can be kept encrypted under independent Keystore aliases. */
    fun keyLabels(): List<String> = prefs.all.keys
        .filter { it.startsWith(NAMED_KEY_PREFIX) }
        .map { it.removePrefix(NAMED_KEY_PREFIX) }
        .sorted()

    fun activeKeyLabel(): String? = prefs.getString(ACTIVE_KEY_LABEL, null)
        ?.takeIf { it in keyLabels() } ?: keyLabels().firstOrNull()

    fun setActiveKey(label: String) {
        require(label in keyLabels()) { "Choose a stored Gemini key." }
        check(prefs.edit().putString(ACTIVE_KEY_LABEL, label).commit()) {
            "Could not change the active Gemini key."
        }
    }

    fun saveNamedKey(label: String, apiKey: String) {
        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank() && cleanLabel.length <= 32) { "Give this API key a short name (1–32 characters)." }
        require(apiKey.isNotBlank()) { "API key cannot be empty." }
        val alias = namedAlias(cleanLabel)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(alias))
        val payload = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val editor = prefs.edit().putString(NAMED_KEY_PREFIX + cleanLabel, Base64.encodeToString(payload, Base64.NO_WRAP))
        if (activeKeyLabel() == null) editor.putString(ACTIVE_KEY_LABEL, cleanLabel)
        check(editor.commit()) { "Could not save the encrypted Gemini key." }
    }

    fun readNamedKey(label: String): String? {
        val encoded = prefs.getString(NAMED_KEY_PREFIX + label, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Stored Gemini credential is corrupted." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingSecretKey(namedAlias(label)), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun removeNamedKey(label: String) {
        val alias = namedAlias(label)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias)
        check(!keyStore.containsAlias(alias)) { "Could not remove the selected Android Keystore key." }
        val next = keyLabels().filterNot { it == label }.firstOrNull()
        val editor = prefs.edit().remove(NAMED_KEY_PREFIX + label)
        if (activeKeyLabel() == label) {
            if (next == null) editor.remove(ACTIVE_KEY_LABEL) else editor.putString(ACTIVE_KEY_LABEL, next)
        }
        check(editor.commit()) { "Could not remove the encrypted Gemini key." }
    }

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
        activeKeyLabel()?.let { return readNamedKey(it) }
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

    private fun namedAlias(label: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8))
        return "oracle.gemini.named." + digest.take(12).joinToString("") { "%02x".format(it) }
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
        const val NAMED_KEY_PREFIX = "gemini_named_key:"
        const val ACTIVE_KEY_LABEL = "gemini_active_key_label"
        const val TAVILY_KEY_CIPHERTEXT = "tavily_api_key_aes_gcm"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
