package studio.oracle.data

import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.DocumentDao
import studio.oracle.data.local.DocumentEntity
import studio.oracle.data.local.DocumentStudyDao
import studio.oracle.data.local.DocumentStudySetEntity
import java.util.UUID

/** Persists generated study aids locally and enforces workspace/source boundaries. */
class DocumentStudyRepository(
    private val documentDao: DocumentDao,
    private val studyDao: DocumentStudyDao,
    private val workspaceId: String
) {
    fun observe(documentId: String): Flow<List<DocumentStudySetEntity>> =
        studyDao.observeForDocument(documentId, workspaceId)

    suspend fun save(
        document: DocumentEntity,
        kind: DocumentStudyKind,
        model: String,
        payload: DocumentStudyPayload,
        sources: List<DocumentStudySource>
    ): DocumentStudySetEntity {
        val currentDocument = documentDao.findDocument(document.id)
            ?: throw IllegalArgumentException("The source document no longer exists.")
        require(currentDocument.workspaceId == workspaceId && document.workspaceId == workspaceId) {
            "Document belongs to a different workspace."
        }
        require(sources.isNotEmpty()) { "A study aid needs at least one source excerpt." }
        val availableChunkIds = documentDao.chunksForDocument(document.id).map { it.id }.toSet()
        require(sources.all { it.chunkId in availableChunkIds }) {
            "A study excerpt does not belong to the selected document."
        }
        val allowedIds = sources.map { it.id }.toSet()
        val contentJson = DocumentStudyCodec.encode(kind, payload)
        DocumentStudyCodec.parse(kind, contentJson, allowedIds)
        val row = DocumentStudySetEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            documentId = document.id,
            kind = kind.wireName,
            title = payload.title,
            model = model,
            contentJson = contentJson,
            sourceManifestJson = DocumentStudySources.manifestJson(sources),
            createdAt = System.currentTimeMillis()
        )
        studyDao.insert(row)
        return row
    }

    suspend fun delete(id: String): Boolean = studyDao.delete(id, workspaceId) > 0
}
