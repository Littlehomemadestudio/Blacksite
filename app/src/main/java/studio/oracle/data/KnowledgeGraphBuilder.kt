package studio.oracle.data

import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.DocumentEntity
import java.security.MessageDigest
import java.util.Locale

data class KnowledgeConceptDraft(
    val id: String,
    val normalizedKey: String,
    val label: String,
    val occurrenceCount: Int,
    val documentCount: Int
)

data class KnowledgeMentionDraft(
    val conceptId: String,
    val documentId: String,
    val chunkId: Long,
    val sourceLabel: String,
    val excerpt: String
)

data class KnowledgeEdgeDraft(
    val id: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    val weight: Int
)

data class KnowledgeGraphDraft(
    val concepts: List<KnowledgeConceptDraft>,
    val mentions: List<KnowledgeMentionDraft>,
    val edges: List<KnowledgeEdgeDraft>,
    val processedChunks: Int,
    val truncated: Boolean
)

/**
 * Builds a transparent, deterministic lexical co-occurrence map from already indexed
 * passages. It does not claim semantic understanding and performs no network requests.
 */
object KnowledgeGraphBuilder {
    const val MAX_GRAPH_CHUNKS = 20_000
    const val MAX_CONCEPTS = 120
    const val MAX_EDGES = 240
    const val MAX_TERMS_PER_CHUNK = 14
    const val MAX_MENTIONS = 6_000
    const val MAX_EXCERPT_CHARS = 220

    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")
    private val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
        "have", "has", "had", "not", "but", "you", "your", "their", "they", "them",
        "then", "than", "into", "onto", "over", "under", "about", "after", "before",
        "when", "where", "what", "which", "who", "how", "why", "can", "could",
        "should", "would", "will", "shall", "may", "might", "must", "also", "only",
        "more", "most", "some", "such", "each", "any", "all", "both", "other",
        "its", "our", "out", "use", "used", "using", "per", "via", "etc",
        "از", "به", "و", "در", "که", "برای", "با", "این", "آن", "را", "است",
        "هست", "هستند", "بود", "بودند", "شد", "شده", "شود", "می", "های", "ها",
        "یک", "تا", "بر", "یا", "اما", "اگر", "هم", "نیز", "هر", "چه", "چون",
        "روی", "بین", "پس", "قبل", "بعد", "ضمن", "بدون", "دارد", "دارند"
    )

    private data class MutableConcept(
        val key: String,
        var label: String,
        var occurrences: Int = 0,
        val documents: MutableSet<String> = linkedSetOf()
    )

    private data class Token(val key: String, val surface: String)

    fun build(
        workspaceId: String,
        documents: List<DocumentEntity>,
        chunksByDocument: Map<String, List<DocumentChunkEntity>>
    ): KnowledgeGraphDraft {
        require(workspaceId.isNotBlank()) { "Workspace identifier is required." }

        val concepts = linkedMapOf<String, MutableConcept>()
        val mentions = linkedMapOf<Pair<String, Long>, KnowledgeMentionDraft>()
        val edgeWeights = linkedMapOf<Pair<String, String>, Int>()
        var processedChunks = 0
        var truncated = false

        for (document in documents.sortedByDescending { it.importedAt }) {
            for (chunk in chunksByDocument[document.id].orEmpty().sortedBy { it.ordinal }) {
                if (processedChunks >= MAX_GRAPH_CHUNKS) {
                    truncated = true
                    break
                }
                processedChunks++

                val tokens = tokenPattern.findAll(chunk.content).mapNotNull { match ->
                    val key = normalize(match.value)
                    if (!isCandidate(key)) null else Token(key, match.value)
                }.toList()
                if (tokens.isEmpty()) continue

                val termCounts = linkedMapOf<String, Pair<String, Int>>()
                fun addTerm(key: String, surface: String) {
                    val current = termCounts[key]
                    termCounts[key] = if (current == null) {
                        surface to 1
                    } else {
                        current.first to current.second + 1
                    }
                }

                tokens.forEach { addTerm(it.key, it.surface) }
                tokens.zipWithNext().forEach { (left, right) ->
                    if (left.key != right.key) {
                        addTerm(left.key + " " + right.key, left.surface + " " + right.surface)
                    }
                }

                val selectedTerms = termCounts.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                            .thenBy { it.key }
                    )
                    .take(MAX_TERMS_PER_CHUNK)

                selectedTerms.forEach { entry ->
                    val key = entry.key
                    val stat = concepts.getOrPut(key) { MutableConcept(key, displayLabel(entry.value.first)) }
                    stat.occurrences += entry.value.second
                    stat.documents += document.id
                    mentions.putIfAbsent(
                        key to chunk.id,
                        KnowledgeMentionDraft(
                            conceptId = stableId(workspaceId, "concept:$key"),
                            documentId = document.id,
                            chunkId = chunk.id,
                            sourceLabel = chunk.sectionLabel.take(100),
                            excerpt = chunk.content.trim().take(MAX_EXCERPT_CHARS)
                        )
                    )
                }

                val keys = selectedTerms.map { it.key }.sorted()
                for (leftIndex in keys.indices) {
                    for (rightIndex in leftIndex + 1 until keys.size) {
                        val pair = keys[leftIndex] to keys[rightIndex]
                        edgeWeights[pair] = (edgeWeights[pair] ?: 0) + 1
                    }
                }
            }
            if (truncated) break
        }

        val selectedConcepts = concepts.values
            .asSequence()
            .filter { it.occurrences >= 2 || it.documents.size >= 2 }
            .sortedWith(
                compareByDescending<MutableConcept> { it.documents.size }
                    .thenByDescending { it.occurrences }
                    .thenBy { it.key }
            )
            .take(MAX_CONCEPTS)
            .toList()
        val selectedIds = selectedConcepts.associate { it.key to stableId(workspaceId, "concept:${it.key}") }
        val selectedKeySet = selectedConcepts.mapTo(hashSetOf()) { it.key }

        val conceptDrafts = selectedConcepts.map { stat ->
            KnowledgeConceptDraft(
                id = selectedIds.getValue(stat.key),
                normalizedKey = stat.key,
                label = displayLabel(stat.label),
                occurrenceCount = stat.occurrences,
                documentCount = stat.documents.size
            )
        }

        val mentionDrafts = mentions.entries
            .asSequence()
            .filter { it.key.first in selectedKeySet }
            .map { (_, value) -> value }
            .take(MAX_MENTIONS)
            .toList()

        val edgeDrafts = edgeWeights.entries
            .asSequence()
            .filter { it.key.first in selectedKeySet && it.key.second in selectedKeySet }
            .sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }
                    .thenBy { it.key.second }
            )
            .take(MAX_EDGES)
            .map { (pair, weight) ->
                KnowledgeEdgeDraft(
                    id = stableId(workspaceId, "edge:${pair.first}:${pair.second}"),
                    sourceConceptId = selectedIds.getValue(pair.first),
                    targetConceptId = selectedIds.getValue(pair.second),
                    weight = weight
                )
            }
            .toList()

        return KnowledgeGraphDraft(
            concepts = conceptDrafts,
            mentions = mentionDrafts,
            edges = edgeDrafts,
            processedChunks = processedChunks,
            truncated = truncated
        )
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .trim()

    private fun isCandidate(value: String): Boolean =
        value.length in 2..48 &&
            value.any { it.isLetter() } &&
            value !in stopWords

    private fun displayLabel(value: String): String =
        value.trim().split(' ').joinToString(" ") { part ->
            part.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
            }
        }

    private fun stableId(workspaceId: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((workspaceId + "|" + value).toByteArray(Charsets.UTF_8))
        return "kg_" + digest.take(16).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
