package studio.oracle.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads provider-backed bytes without trusting ContentResolver size metadata.
 * The byte limit is enforced while streaming, before memory can grow past it.
 */
internal object BoundedInputReader {
    fun read(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes in 0L..Int.MAX_VALUE.toLong()) { "Invalid input-read limit." }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE.toLong()).toInt())
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maxBytes - count) {
                throw IOException("Input exceeds the " + (maxBytes / (1024L * 1024L)) + " MB read limit.")
            }
            total += count
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val BUFFER_SIZE = 8 * 1024
}

/** Strict UTF-8 decoding layered on top of the bounded streaming reader. */
internal object BoundedUtf8Reader {
    fun read(input: InputStream, maxBytes: Long): String {
        val bytes = BoundedInputReader.read(input, maxBytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IOException("The selected file is not valid UTF-8 text.")
        }
    }
}
