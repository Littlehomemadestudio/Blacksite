package studio.oracle.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Captures mono 16 kHz PCM and writes a standards-compliant WAV file. */
class WavAudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var writerFailure: String? = null
    private var rawFile: File? = null
    private var targetFile: File? = null

    fun start(outputFile: File) {
        check(!recording && audioRecord == null) { "A recording is already active." }
        val parent = outputFile.parentFile ?: throw IOException("Recording path has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the audio directory.")

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IOException("This device does not expose a compatible microphone input.")

        val capture = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        if (capture.state != AudioRecord.STATE_INITIALIZED) {
            capture.release()
            throw IOException("The microphone could not be initialized.")
        }

        val raw = File(parent, outputFile.name + ".pcm.tmp")
        raw.delete()
        outputFile.delete()
        try {
            capture.startRecording()
            if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Android did not start microphone capture.")
            }
        } catch (error: Exception) {
            capture.release()
            throw IOException("Microphone capture could not start.", error)
        }

        audioRecord = capture
        rawFile = raw
        targetFile = outputFile
        writerFailure = null
        recording = true
        writerThread = Thread({
            try {
                raw.outputStream().buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(maxOf(minBuffer, 4096))
                    while (recording) {
                        val count = capture.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            output.write(buffer, 0, count)
                        } else if (recording && count < 0) {
                            throw IOException("Microphone capture stopped unexpectedly.")
                        }
                    }
                    output.flush()
                }
            } catch (error: Exception) {
                writerFailure = error.message ?: "Audio capture failed."
                recording = false
            }
        }, "oracle-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop(): File {
        val capture = audioRecord ?: throw IllegalStateException("No recording is active.")
        val raw = rawFile ?: throw IllegalStateException("Recording buffer is unavailable.")
        val outputFile = targetFile ?: throw IllegalStateException("Recording destination is unavailable.")
        recording = false
        runCatching {
            if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop()
        }
        val thread = writerThread
        thread?.join(5_000L)
        if (thread?.isAlive == true) {
            capture.release()
            audioRecord = null
            raw.delete()
            outputFile.delete()
            throw IOException("Microphone capture did not stop cleanly.")
        }
        capture.release()
        audioRecord = null
        writerThread = null

        writerFailure?.let { message ->
            raw.delete()
            outputFile.delete()
            rawFile = null
            targetFile = null
            throw IOException(message)
        }

        val dataLength = raw.length()
        if (dataLength <= 0L || dataLength > MAX_PCM_BYTES || dataLength > Int.MAX_VALUE - 36L) {
            raw.delete()
            outputFile.delete()
            rawFile = null
            targetFile = null
            throw IOException("The recording is empty or exceeded the capture limit.")
        }

        FileOutputStream(outputFile).use { output ->
            output.write(WavHeader.create(dataLength.toInt()))
            raw.inputStream().use { input -> input.copyTo(output) }
            output.fd.sync()
        }
        raw.delete()
        rawFile = null
        targetFile = null
        return outputFile
    }

    @Synchronized
    fun cancel() {
        recording = false
        val capture = audioRecord
        runCatching {
            if (capture?.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop()
        }
        runCatching { writerThread?.join(1_000L) }
        runCatching { capture?.release() }
        audioRecord = null
        writerThread = null
        rawFile?.delete()
        targetFile?.delete()
        rawFile = null
        targetFile = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 10 * 60 * 1_000L
        private const val MAX_PCM_BYTES = 28L * 1024L * 1024L
    }
}

/** Pure WAV-header builder, kept separate for deterministic unit testing. */
object WavHeader {
    fun create(
        dataLength: Int,
        sampleRate: Int = WavAudioRecorder.SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        require(dataLength >= 0 && dataLength <= Int.MAX_VALUE - 36)
        require(sampleRate > 0 && channels > 0 && bitsPerSample > 0)
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(36 + dataLength)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(dataLength)
        }.array()
    }
}
