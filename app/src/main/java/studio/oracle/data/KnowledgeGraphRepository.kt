package studio.oracle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.oracle.data.local.DocumentDao
import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.KnowledgeConceptEntity
import studio.oracle.data.local.KnowledgeEdgeEntity
import studio.oracle.data.local.KnowledgeGraphDao
import studio.oracle.data.local.KnowledgeMentionEntity

data class KnowledgeGraphSnapshot(
    val concepts: List<KnowledgeConceptEntity>,
    val edges: List<KnowledgeEdgeEntity>
)

data class KnowledgeGraphBuildResult(
    val documentCount: Int,
    val processedChunks: Int,
    val conceptCount: Int,
    val edgeCount: Int,
    val mentionCount: Int,
    val truncated: Boolean
)

/** Workspace-scoped persistence for the offline lexical knowledge map. */
class KnowledgeGraphRepository(
    private val documentDao: DocumentDao,
    private val graphDao: KnowledgeGraphDao,
    private val workspaceId: String
) {
    suspend fun rebuild(): KnowledgeGraphBuildResult = withContext(Dispatchers.IO) {
        require(workspaceId.isNotBlank()) { "Workspace identifier is required." }

        val documents = documentDao.documentsSnapshot(workspaceId)
        val chunksByDocument = linkedMapOf<String, List<DocumentChunkEntity>>()
        var remainingChunks = KnowledgeGraphBuilder.MAX_GRAPH_CHUNKS
        var truncatedByDocumentLimit = false

        for (document in documents) {
            if (remainingChunks <= 0) {
                truncatedByDocumentLimit = true
                break
            }
            val chunks = documentDao.chunksForDocument(document.id)
            if (chunks.size > remainingChunks) truncatedByDocumentLimit = true
            val selected = chunks.take(remainingChunks)
            chunksByDocument[document.id] = selected
            remainingChunks -= selected.size
        }

        val draft = KnowledgeGraphBuilder.build(
            workspaceId = workspaceId,
            documents = documents,
            chunksByDocument = chunksByDocument
        )
        val conceptEntities = draft.concepts.map { concept ->
            KnowledgeConceptEntity(
                id = concept.id,
                workspaceId = workspaceId,
                normalizedKey = concept.normalizedKey,
                label = concept.label,
                occurrenceCount = concept.occurrenceCount,
                documentCount = concept.documentCount,
                updatedAt = System.currentTimeMillis()
            )
        }
        val mentionEntities = draft.mentions.map { mention ->
            KnowledgeMentionEntity(
                workspaceId = workspaceId,
                conceptId = mention.conceptId,
                documentId = mention.documentId,
                chunkId = mention.chunkId,
                sourceLabel = mention.sourceLabel,
                excerpt = mention.excerpt
            )
        }
        val edgeEntities = draft.edges.map { edge ->
            KnowledgeEdgeEntity(
                id = edge.id,
                workspaceId = workspaceId,
                sourceConceptId = edge.sourceConceptId,
                targetConceptId = edge.targetConceptId,
                weight = edge.weight
            )
        }

        graphDao.replaceWorkspaceGraph(workspaceId, conceptEntities, mentionEntities, edgeEntities)
        KnowledgeGraphBuildResult(
            documentCount = chunksByDocument.size,
            processedChunks = draft.processedChunks,
            conceptCount = conceptEntities.size,
            edgeCount = edgeEntities.size,
            mentionCount = mentionEntities.size,
            truncated = draft.truncated || truncatedByDocumentLimit
        )
    }

    suspend fun snapshot(): KnowledgeGraphSnapshot = withContext(Dispatchers.IO) {
        KnowledgeGraphSnapshot(
            concepts = graphDao.conceptsSnapshot(workspaceId),
            edges = graphDao.edgesSnapshot(workspaceId)
        )
    }

    suspend fun mentionsForConcept(conceptId: String): List<KnowledgeMentionEntity> =
        withContext(Dispatchers.IO) {
            if (graphDao.findConcept(conceptId, workspaceId) == null) {
                emptyList()
            } else {
                graphDao.mentionsForConcept(conceptId, workspaceId)
            }
        }
}
