package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.DocumentEntity

class KnowledgeGraphBuilderTest {
    @Test
    fun repeatedTermsCreateStableConceptsEdgesAndCitedSourceMentions() {
        val first = document("doc-a", "workspace-a", 20L)
        val second = document("doc-b", "workspace-a", 10L)
        val chunks = mapOf(
            first.id to listOf(
                chunk(11L, first.id, 0, "Page 1", "Neural networks learn patterns. Neural networks require training data."),
                chunk(12L, first.id, 1, "Page 2", "Neural networks use training data to learn patterns.")
            ),
            second.id to listOf(
                chunk(21L, second.id, 0, "Slide 1", "Neural networks learn patterns from training data.")
            )
        )

        val result = KnowledgeGraphBuilder.build("workspace-a", listOf(first, second), chunks)
        val repeatedPhrase = result.concepts.single { it.normalizedKey == "neural networks" }

        assertEquals(2, repeatedPhrase.documentCount)
        assertTrue(repeatedPhrase.occurrenceCount >= 3)
        assertTrue(result.edges.any {
            it.sourceConceptId == repeatedPhrase.id || it.targetConceptId == repeatedPhrase.id
        })
        assertTrue(result.mentions.any { it.conceptId == repeatedPhrase.id && it.sourceLabel == "Page 1" })
        assertTrue(result.mentions.any { it.conceptId == repeatedPhrase.id && it.sourceLabel == "Slide 1" })

        val rebuilt = KnowledgeGraphBuilder.build("workspace-a", listOf(first, second), chunks)
        assertEquals(result.concepts.map { it.id }, rebuilt.concepts.map { it.id })
        assertEquals(result.edges.map { it.id }, rebuilt.edges.map { it.id })
        assertFalse(result.truncated)
    }

    @Test
    fun arabicAndPersianYehAndKafNormalizeToTheSameConcept() {
        val first = document("doc-a", "workspace-a", 2L)
        val second = document("doc-b", "workspace-a", 1L)
        val result = KnowledgeGraphBuilder.build(
            "workspace-a",
            listOf(first, second),
            mapOf(
                first.id to listOf(chunk(1L, first.id, 0, "Page 1", "يادگيري ماشين")),
                second.id to listOf(chunk(2L, second.id, 0, "Page 1", "یادگیری ماشین"))
            )
        )

        assertTrue(result.concepts.any { it.normalizedKey == "یادگیری ماشین" && it.documentCount == 2 })
    }

    @Test
    fun graphOutputRespectsNodeEdgeAndMentionLimits() {
        val source = document("doc-large", "workspace-a", 1L)
        val chunks = (0 until 200).map { index ->
            val terms = (0 until 12).joinToString(" ") { term -> "concept${index}_${term} concept${index}_${term}" }
            chunk(index + 1L, source.id, index, "Section ${index + 1}", terms)
        }
        val result = KnowledgeGraphBuilder.build("workspace-a", listOf(source), mapOf(source.id to chunks))

        assertTrue(result.concepts.size <= KnowledgeGraphBuilder.MAX_CONCEPTS)
        assertTrue(result.edges.size <= KnowledgeGraphBuilder.MAX_EDGES)
        assertTrue(result.mentions.size <= KnowledgeGraphBuilder.MAX_MENTIONS)
    }

    private fun document(id: String, workspaceId: String, importedAt: Long) = DocumentEntity(
        id = id,
        title = id,
        fileName = "$id.txt",
        mimeType = "text/plain",
        storagePath = "documents/$id.txt",
        sizeBytes = 100L,
        pageCount = 1,
        extractedCharacters = 100,
        importedAt = importedAt,
        workspaceId = workspaceId
    )

    private fun chunk(
        id: Long,
        documentId: String,
        ordinal: Int,
        label: String,
        content: String
    ) = DocumentChunkEntity(
        id = id,
        documentId = documentId,
        ordinal = ordinal,
        pageNumber = null,
        sectionLabel = label,
        content = content
    )
}
