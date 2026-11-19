package studio.oracle.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal data class PdfOcrResult(
    val pages: List<ExtractedPage>,
    val recognizedPageCount: Int,
    val failedPageCount: Int,
    val unreadablePageCount: Int,
    val skippedPageCount: Int
)

/**
 * Bundled ML Kit Latin-script OCR. Processing is local: this class has no network
 * client and never uploads source files or extracted text.
 */
internal object OnDeviceOcr {
    suspend fun recognizeImage(file: File): String {
        val bitmap = decodeSampledImage(file)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognize(recognizer, bitmap)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    suspend fun recognizeBlankPdfPages(
        file: File,
        pages: List<ExtractedPage>
    ): PdfOcrResult {
        val targets = OcrImportPolicy.pdfPagesRequiringOcr(pages)
        val blankPageCount = pages.count { it.text.isBlank() }
        val skipped = (blankPageCount - targets.size).coerceAtLeast(0)
        if (targets.isEmpty()) {
            return PdfOcrResult(pages, 0, 0, 0, skipped)
        }

        val output = pages.toMutableList()
        var recognized = 0
        var failed = 0
        var attempted = 0

        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        for (index in targets) {
                            currentCoroutineContext().ensureActive()
                            attempted += 1
                            if (index >= renderer.pageCount) {
                                failed += 1
                                continue
                            }

                            var page: PdfRenderer.Page? = null
                            var bitmap: Bitmap? = null
                            try {
                                page = renderer.openPage(index)
                                val (width, height) = OcrImportPolicy.pdfRenderSize(page.width, page.height)
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                                val text = recognize(recognizer, bitmap)
                                if (text.isNotBlank()) {
                                    output[index] = output[index].copy(text = text)
                                    recognized += 1
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed += 1
                            } finally {
                                bitmap?.recycle()
                                runCatching { page?.close() }
                            }
                        }
                    } finally {
                        recognizer.close()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A renderer/model initialization failure affects only the still-unattempted
            // image-only pages; selectable text already extracted from PDFBox is retained.
            failed += (targets.size - attempted).coerceAtLeast(0)
        }

        val unreadable = (targets.size - recognized - failed).coerceAtLeast(0)
        return PdfOcrResult(output, recognized, failed, unreadable, skipped)
    }

    private fun decodeSampledImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected image could not be decoded.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = OcrImportPolicy.imageSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("The selected image could not be decoded.")
    }

    private suspend fun recognize(
        recognizer: TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCoroutine { continuation ->
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { failure -> continuation.resumeWithException(failure) }
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
        }
    }
}
