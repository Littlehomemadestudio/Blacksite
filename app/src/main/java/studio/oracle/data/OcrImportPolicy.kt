package studio.oracle.data

import kotlin.math.roundToInt

/**
 * Deterministic resource limits for on-device OCR. These limits keep image decoding
 * and scanned-PDF rendering bounded on mid-range phones.
 */
object OcrImportPolicy {
    const val MAX_PDF_OCR_PAGES = 100
    const val MAX_IMAGE_DIMENSION = 2_200
    const val MAX_RENDER_DIMENSION = 2_200
    const val MAX_PDF_RENDER_SCALE = 2.5f

    fun pdfPagesRequiringOcr(
        pages: List<ExtractedPage>,
        maxPages: Int = MAX_PDF_OCR_PAGES
    ): List<Int> {
        if (maxPages <= 0) return emptyList()
        return pages.indices.filter { pages[it].text.isBlank() }.take(maxPages)
    }

    fun imageSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_IMAGE_DIMENSION
    ): Int {
        require(maxDimension > 0) { "Maximum image dimension must be positive." }
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            if (sample > Int.MAX_VALUE / 2) break
            sample *= 2
        }
        return sample
    }

    fun pdfRenderSize(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_RENDER_DIMENSION,
        maxScale: Float = MAX_PDF_RENDER_SCALE
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "PDF page dimensions must be positive." }
        require(maxDimension > 0 && maxScale > 0f) { "OCR render limits must be positive." }
        val scale = minOf(maxScale, maxDimension.toFloat() / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}
