package studio.oracle.security

import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioFileCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")
    private val cipher = LocalAudioFileCipher { key }

    @Test
    fun encryptedPayloadRoundTripsWithoutExposingPlaintext() {
        val directory = Files.createTempDirectory("oracle-audio-cipher").toFile()
        try {
            val plaintext = ByteArray(32_768) { ((it * 31) % 251).toByte() }
            val source = File(directory, "source.wav").apply { writeBytes(plaintext) }
            val encrypted = File(directory, "record.wav.enc")
            val restored = File(directory, "restored.wav")

            cipher.encrypt(source, encrypted, "12345678-1234-1234-1234-123456789012")

            assertTrue(encrypted.isFile)
            assertFalse(encrypted.readBytes().contentEquals(plaintext))
            assertTrue(encrypted.readBytes().copyOfRange(0, 4).contentEquals(byteArrayOf(0x4f, 0x52, 0x41, 0x46)))
            cipher.decrypt(encrypted, restored, "12345678-1234-1234-1234-123456789012")
            assertArrayEquals(plaintext, restored.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun authenticatedRecordIdPreventsCiphertextSwapping() {
        val directory = Files.createTempDirectory("oracle-audio-id").toFile()
        try {
            val source = File(directory, "source.wav").apply { writeBytes(ByteArray(512) { it.toByte() }) }
            val encrypted = File(directory, "record.wav.enc")
            val restored = File(directory, "restored.wav")
            cipher.encrypt(source, encrypted, "12345678-1234-1234-1234-123456789012")

            assertThrows(IOException::class.java) {
                cipher.decrypt(encrypted, restored, "87654321-4321-4321-4321-210987654321")
            }
            assertFalse(restored.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun modifiedCiphertextIsRejectedAndPartialPlaintextIsRemoved() {
        val directory = Files.createTempDirectory("oracle-audio-tamper").toFile()
        try {
            val source = File(directory, "source.wav").apply { writeBytes(ByteArray(1_024) { (it % 239).toByte() }) }
            val encrypted = File(directory, "record.wav.enc")
            val restored = File(directory, "restored.wav")
            cipher.encrypt(source, encrypted, "12345678-1234-1234-1234-123456789012")
            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x40).toByte()
            encrypted.writeBytes(bytes)

            assertThrows(IOException::class.java) {
                cipher.decrypt(encrypted, restored, "12345678-1234-1234-1234-123456789012")
            }
            assertFalse(restored.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
