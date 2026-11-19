package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrImportPolicyTest {
    @Test
    fun onlyBlankPdfPagesAreSelectedAndThePageCapIsApplied() {
        val pages = listOf(
            ExtractedPage(1, "Page 1", "Selectable text"),
            ExtractedPage(2, "Page 2", "  "),
            ExtractedPage(3, "Page 3", "More selectable text"),
            ExtractedPage(4, "Page 4", "\n"),
            ExtractedPage(5, "Page 5", "")
        )

        assertEquals(listOf(1, 3, 4), OcrImportPolicy.pdfPagesRequiringOcr(pages))
        assertEquals(listOf(1), OcrImportPolicy.pdfPagesRequiringOcr(pages, maxPages = 1))
        assertTrue(OcrImportPolicy.pdfPagesRequiringOcr(pages, maxPages = 0).isEmpty())
    }

    @Test
    fun imageSamplingBoundsTheDecodedLongestDimension() {
        assertEquals(1, OcrImportPolicy.imageSampleSize(1_200, 800))
        val sample = OcrImportPolicy.imageSampleSize(8_000, 6_000)
        assertTrue(sample > 1)
        assertTrue(8_000 / sample <= OcrImportPolicy.MAX_IMAGE_DIMENSION)
        assertTrue(6_000 / sample <= OcrImportPolicy.MAX_IMAGE_DIMENSION)
    }

    @Test
    fun pdfRenderingPreservesAspectRatioWithinTheBitmapLimit() {
        val (width, height) = OcrImportPolicy.pdfRenderSize(612, 792)
        assertTrue(width > 0 && height > 0)
        assertTrue(maxOf(width, height) <= OcrImportPolicy.MAX_RENDER_DIMENSION)
        assertTrue(kotlin.math.abs(width.toFloat() / height - 612f / 792f) < 0.002f)

        val (largeWidth, largeHeight) = OcrImportPolicy.pdfRenderSize(5_000, 7_000)
        assertTrue(maxOf(largeWidth, largeHeight) <= OcrImportPolicy.MAX_RENDER_DIMENSION)
    }
}
