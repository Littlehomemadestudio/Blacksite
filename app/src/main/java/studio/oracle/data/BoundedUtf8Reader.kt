package studio.oracle.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads provider-backed text without trusting ContentResolver/DocumentFile size metadata.
 * The byte limit is enforced while streaming, and malformed UTF-8 is rejected.
 */
internal object BoundedUtf8Reader {
    fun read(input: InputStream, maxBytes: Long): String {
        require(maxBytes in 0L..Int.MAX_VALUE.toLong()) { "Invalid text-read limit." }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE.toLong()).toInt())
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maxBytes - count) {
                throw IOException("File exceeds ORACLE's " + (maxBytes / (1024L * 1024L)) + " MB text-read limit.")
            }
            total += count
            output.write(buffer, 0, count)
        }

        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IOException("The selected file is not valid UTF-8 text.")
        }
    }

    private const val BUFFER_SIZE = 8 * 1024
}
