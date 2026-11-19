package studio.oracle.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streaming AES-GCM encryption for local Audio Memory payloads.
 *
 * Each file is authenticated against its recording ID, so ciphertext cannot be silently
 * swapped between records. The AES key is non-exportable and owned by Android Keystore.
 */
class LocalAudioFileCipher(
    private val keyProvider: () -> SecretKey = { getOrCreateKey() }
) {
    fun encrypt(source: File, destination: File, recordId: String) {
        validateRecordId(recordId)
        if (!source.isFile || source.length() > MAX_PLAINTEXT_BYTES) {
            throw IOException("The source audio file is missing or exceeds the local encryption limit.")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(authenticatedData(recordId))
        val iv = cipher.iv ?: throw IOException("Android Keystore did not provide an AES-GCM nonce.")
        if (iv.size != GCM_IV_BYTES) throw IOException("Android Keystore returned an unsupported AES-GCM nonce.")

        writeAtomically(destination) { temporary ->
            FileOutputStream(temporary).use { raw ->
                raw.write(MAGIC)
                raw.write(FORMAT_VERSION)
                raw.write(iv.size)
                raw.write(iv)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    FileInputStream(source).use { input ->
                        copyBounded(input, encrypted, MAX_PLAINTEXT_BYTES)
                    }
                }
            }
        }
    }

    fun decrypt(source: File, destination: File, recordId: String) {
        validateRecordId(recordId)
        if (!source.isFile || source.length() !in MIN_ENCRYPTED_BYTES..MAX_ENCRYPTED_BYTES) {
            throw IOException("The encrypted audio file is missing, incomplete, or exceeds the supported limit.")
        }

        writeAtomically(destination) { temporary ->
            FileInputStream(source).use { raw ->
                val magic = ByteArray(MAGIC.size)
                readFully(raw, magic)
                if (!magic.contentEquals(MAGIC)) throw IOException("This is not an ORACLE encrypted audio file.")
                val version = raw.read()
                if (version != FORMAT_VERSION) throw IOException("This ORACLE audio encryption version is not supported.")
                val ivLength = raw.read()
                if (ivLength != GCM_IV_BYTES) throw IOException("The encrypted audio header is invalid.")
                val iv = ByteArray(ivLength)
                readFully(raw, iv)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(authenticatedData(recordId))
                FileOutputStream(temporary).use { output ->
                    decryptAuthenticated(raw, output, cipher)
                }
            }
        }
    }

    private fun writeAtomically(destination: File, writer: (File) -> Unit) {
        val parent = destination.parentFile ?: throw IOException("The encrypted audio destination is invalid.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the encrypted audio directory.")
        if (!parent.isDirectory) throw IOException("The encrypted audio destination is not a directory.")
        val temporary = File(parent, destination.name + "." + UUID.randomUUID() + ".tmp")
        try {
            writer(temporary)
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the previous audio file.")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not atomically finalize the audio file.")
            }
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            copied += count
            if (copied > maximum) throw IOException("The audio payload exceeds the local encryption limit.")
            output.write(buffer, 0, count)
        }
    }

    private fun decryptAuthenticated(input: InputStream, output: OutputStream, cipher: Cipher) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val plaintext = cipher.update(buffer, 0, count) ?: continue
            copied += plaintext.size
            if (copied > MAX_PLAINTEXT_BYTES) throw IOException("The decrypted audio exceeds the supported limit.")
            output.write(plaintext)
        }
        // GCM authentication is only final after the tag is checked by doFinal().
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) {
            copied += finalBytes.size
            if (copied > MAX_PLAINTEXT_BYTES) throw IOException("The decrypted audio exceeds the supported limit.")
            output.write(finalBytes)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("The encrypted audio header is truncated.")
            offset += count
        }
    }

    private fun authenticatedData(recordId: String): ByteArray =
        ("ORACLE-AUDIO:" + FORMAT_VERSION + ":" + recordId).toByteArray(Charsets.UTF_8)

    private fun validateRecordId(recordId: String) {
        require(recordId.matches(RECORD_ID_PATTERN)) { "Recording identifier is invalid." }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "oracle.audio.files.aes.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val COPY_BUFFER_BYTES = 8 * 1024
        const val MAX_PLAINTEXT_BYTES = 30L * 1024L * 1024L
        val MAGIC = byteArrayOf(0x4f, 0x52, 0x41, 0x46) // ORAF
        val MIN_ENCRYPTED_BYTES = MAGIC.size + 1L + 1L + GCM_IV_BYTES + GCM_TAG_BITS / 8
        val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + MIN_ENCRYPTED_BYTES
        val RECORD_ID_PATTERN = Regex("[A-Za-z0-9-]{20,40}")

        fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
