package studio.oracle.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavHeaderTest {
    @Test
    fun createsPcmWaveHeaderWithCorrectLengthsAndFormat() {
        val dataLength = 32_000
        val header = WavHeader.create(dataLength)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(header.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(header.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(header.copyOfRange(36, 40), Charsets.US_ASCII))
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + dataLength, values.getInt(4))
        assertEquals(16_000, values.getInt(24))
        assertEquals(32_000, values.getInt(28))
        assertEquals(1.toShort(), values.getShort(22))
        assertEquals(2.toShort(), values.getShort(32))
        assertEquals(16.toShort(), values.getShort(34))
        assertEquals(dataLength, values.getInt(40))
    }

    @Test
    fun rejectsInvalidPayloadLengths() {
        assertTrue(runCatching { WavHeader.create(-1) }.isFailure)
    }
}
