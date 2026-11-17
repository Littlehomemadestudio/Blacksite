package studio.oracle.data

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedUtf8ReaderTest {
    @Test
    fun readsValidUtf8IncludingMultibyteCharacters() {
        val expected = "ORACLE · سلام · 日本語"
        val actual = BoundedUtf8Reader.read(
            ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)),
            128L
        )

        assertEquals(expected, actual)
    }

    @Test
    fun acceptsAStreamExactlyAtTheByteLimit() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        assertEquals("hello", BoundedUtf8Reader.read(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test
    fun rejectsAStreamThatExceedsTheByteLimitEvenWhenItHasNoSizeMetadata() {
        val error = runCatching {
            BoundedUtf8Reader.read(ByteArrayInputStream(ByteArray(1024)), 128L)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("text-read limit"))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfSilentlyReplacingBytes() {
        val error = runCatching {
            BoundedUtf8Reader.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), 16L)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("valid UTF-8"))
    }
}
