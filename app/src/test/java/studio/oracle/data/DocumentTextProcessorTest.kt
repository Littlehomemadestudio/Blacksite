package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextProcessorTest {
    @Test
    fun chunksRespectTheSizeLimitAndRetainPdfPageCitations() {
        val text = (1..900).joinToString(" ") { "knowledge" }
        val chunks = DocumentTextProcessor.chunk(
            listOf(ExtractedPage(pageNumber = 7, label = "Page 7", text = text))
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.content.length <= DocumentTextProcessor.MAX_CHUNK_CHARS })
        assertTrue(chunks.all { it.pageNumber == 7 && it.sectionLabel == "Page 7" })
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
    }

    @Test
    fun textDocumentsReceiveStableSectionCitations() {
        val text = (1..500).joinToString("\n\n") { "Section content number $it with useful detail." }
        val chunks = DocumentTextProcessor.chunk(
            listOf(ExtractedPage(pageNumber = null, label = "Document text", text = text))
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.pageNumber == null })
        assertTrue(chunks.all { it.sectionLabel.isNotBlank() })
        assertEquals(chunks.size, chunks.map { it.sectionLabel }.distinct().size)
    }

    @Test
    fun localRankingSupportsEnglishAndPersianTerms() {
        assertTrue(DocumentTextProcessor.score("neural network", "Neural networks learn from data.") >= 20)
        assertTrue(DocumentTextProcessor.score("هوش مصنوعی", "این متن درباره هوش مصنوعی است.") >= 20)
        assertEquals(0, DocumentTextProcessor.score("astronomy", "A document about geometry."))
    }

    @Test
    fun longUnbrokenTokensAreSplitWithoutDroppingTheTail() {
        val text = "x".repeat(2_500)
        val chunks = DocumentTextProcessor.chunk(
            listOf(ExtractedPage(pageNumber = null, label = "Document text", text = text))
        )

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.content.isNotEmpty() })
        assertTrue(chunks.last().content.length >= 200)
    }

    @Test
    fun blankTextProducesNoChunksOrSearchTokens() {
        assertTrue(DocumentTextProcessor.chunk(listOf(ExtractedPage(null, "Document text", "  \n  "))).isEmpty())
        assertTrue(DocumentTextProcessor.tokenize("!? ...").isEmpty())
    }
}
