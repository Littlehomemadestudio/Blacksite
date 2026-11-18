package studio.oracle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class DocumentStudyRepositoryTest {
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
    fun studySetsPersistInsideTheirWorkspaceAndCascadeWithSourceDocument() = runBlocking {
        val documentDao = database.documentDao()
        val document = document("doc-a", "workspace-a")
        val chunk = chunk(11L, document.id, 0)
        documentDao.saveDocumentWithChunks(document, listOf(chunk))
        val repository = DocumentStudyRepository(
            documentDao,
            database.documentStudyDao(),
            "workspace-a"
        )
        val sources = listOf(DocumentStudySource("S1", "Page 1", chunk.id, chunk.content))
        val payload = DocumentStudyPayload(
            title = "Cell biology",
            summary = "Cells are the basic unit of life.",
            keyPoints = listOf(
                StudyPoint("Cells form living organisms.", listOf("S1")),
                StudyPoint("Membranes regulate exchange.", listOf("S1")),
                StudyPoint("Organelles have specialised roles.", listOf("S1"))
            )
        )

        val saved = repository.save(document, DocumentStudyKind.SUMMARY, "gemini-test", payload, sources)

        assertEquals("workspace-a", saved.workspaceId)
        assertEquals(saved.id, repository.observe(document.id).first().single().id)
        assertEquals(1, DocumentStudySources.readManifest(saved.sourceManifestJson).size)

        documentDao.deleteDocument(document.id)
        assertTrue(repository.observe(document.id).first().isEmpty())
    }

    @Test
    fun studyRepositoryRejectsForeignWorkspaceAndForeignDocumentChunks() = runBlocking {
        val documentDao = database.documentDao()
        val ownDocument = document("doc-own", "workspace-a")
        val foreignDocument = document("doc-foreign", "workspace-b")
        val ownChunk = chunk(21L, ownDocument.id, 0)
        val foreignChunk = chunk(22L, foreignDocument.id, 0)
        documentDao.saveDocumentWithChunks(ownDocument, listOf(ownChunk))
        documentDao.saveDocumentWithChunks(foreignDocument, listOf(foreignChunk))

        val ownRepository = DocumentStudyRepository(documentDao, database.documentStudyDao(), "workspace-a")
        val foreignWorkspaceRepository = DocumentStudyRepository(documentDao, database.documentStudyDao(), "workspace-b")
        val payload = DocumentStudyPayload(
            title = "Study",
            summary = "Summary",
            keyPoints = listOf(
                StudyPoint("Point one", listOf("S1")),
                StudyPoint("Point two", listOf("S1")),
                StudyPoint("Point three", listOf("S1"))
            )
        )

        val wrongWorkspace = runCatching {
            foreignWorkspaceRepository.save(
                ownDocument,
                DocumentStudyKind.SUMMARY,
                "model",
                payload,
                listOf(DocumentStudySource("S1", "Page 1", ownChunk.id, ownChunk.content))
            )
        }.exceptionOrNull()
        val wrongChunk = runCatching {
            ownRepository.save(
                ownDocument,
                DocumentStudyKind.SUMMARY,
                "model",
                payload,
                listOf(DocumentStudySource("S1", "Foreign page", foreignChunk.id, foreignChunk.content))
            )
        }.exceptionOrNull()

        assertTrue(wrongWorkspace is IllegalArgumentException)
        assertTrue(wrongChunk is IllegalArgumentException)
        assertTrue(ownRepository.observe(ownDocument.id).first().isEmpty())
    }

    private fun document(id: String, workspaceId: String) = DocumentEntity(
        id = id,
        title = id,
        fileName = id + ".txt",
        mimeType = "text/plain",
        storagePath = "documents/" + id + ".txt",
        sizeBytes = 40L,
        pageCount = 1,
        extractedCharacters = 40,
        importedAt = 1L,
        workspaceId = workspaceId
    )

    private fun chunk(id: Long, documentId: String, ordinal: Int) = DocumentChunkEntity(
        id = id,
        documentId = documentId,
        ordinal = ordinal,
        pageNumber = null,
        sectionLabel = "Section " + (ordinal + 1),
        content = "A source passage with study content."
    )
}
