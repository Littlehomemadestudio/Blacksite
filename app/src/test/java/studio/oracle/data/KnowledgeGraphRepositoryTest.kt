package studio.oracle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.DocumentEntity
import studio.oracle.data.local.OracleDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeGraphRepositoryTest {
    private lateinit var database: OracleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OracleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun graphIsWorkspaceScopedAndSourceDeletionRemovesItsMentions() = runBlocking {
        val documentDao = database.documentDao()
        val own = document("own", "workspace-a")
        val foreign = document("foreign", "workspace-b")
        documentDao.saveDocumentWithChunks(
            own,
            listOf(
                chunk(own.id, 0, "Page 1", "Neural networks learn patterns. Neural networks need training data."),
                chunk(own.id, 1, "Page 2", "Neural networks improve with training data.")
            )
        )
        documentDao.saveDocumentWithChunks(
            foreign,
            listOf(
                chunk(foreign.id, 0, "Page 1", "Neural networks learn patterns. Neural networks need training data.")
            )
        )

        val ownRepository = KnowledgeGraphRepository(documentDao, database.knowledgeGraphDao(), "workspace-a")
        val foreignRepository = KnowledgeGraphRepository(documentDao, database.knowledgeGraphDao(), "workspace-b")

        val ownBuild = ownRepository.rebuild()
        val foreignBeforeBuild = foreignRepository.snapshot()

        assertTrue(ownBuild.conceptCount > 0)
        assertTrue(foreignBeforeBuild.concepts.isEmpty())
        val ownSnapshot = ownRepository.snapshot()
        val concept = ownSnapshot.concepts.first { it.normalizedKey == "neural networks" }
        val ownMentions = ownRepository.mentionsForConcept(concept.id)
        assertTrue(ownMentions.isNotEmpty())
        assertTrue(ownMentions.all { it.documentId == own.id && it.workspaceId == "workspace-a" })

        documentDao.deleteDocument(own.id)
        ownRepository.rebuild()

        assertTrue(ownRepository.snapshot().concepts.isEmpty())
        assertEquals(0, ownRepository.mentionsForConcept(concept.id).size)
        assertTrue(foreignRepository.snapshot().concepts.isEmpty())
    }

    private fun document(id: String, workspaceId: String) = DocumentEntity(
        id = id,
        title = id,
        fileName = "$id.txt",
        mimeType = "text/plain",
        storagePath = "documents/$id.txt",
        sizeBytes = 100L,
        pageCount = 1,
        extractedCharacters = 100,
        importedAt = 1L,
        workspaceId = workspaceId
    )

    private fun chunk(documentId: String, ordinal: Int, label: String, content: String) =
        DocumentChunkEntity(
            documentId = documentId,
            ordinal = ordinal,
            pageNumber = null,
            sectionLabel = label,
            content = content
        )
}
