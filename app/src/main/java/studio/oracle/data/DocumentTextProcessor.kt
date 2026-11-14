package studio.oracle.data

import java.util.Locale

data class ExtractedPage(
    val pageNumber: Int?,
    val label: String,
    val text: String
)

data class DocumentChunkDraft(
    val ordinal: Int,
    val pageNumber: Int?,
    val sectionLabel: String,
    val content: String
)

/**
 * Deterministic, offline text segmentation and retrieval helpers.
 * PDF pages retain their original page numbers; text files use stable section labels.
 */
object DocumentTextProcessor {
    const val MAX_CHUNK_CHARS = 1_200
    const val OVERLAP_CHARS = 180
    const val MAX_CHUNKS = 4_000

    fun chunk(pages: List<ExtractedPage>): List<DocumentChunkDraft> {
        val output = mutableListOf<DocumentChunkDraft>()
        pages.forEach { page ->
            if (output.size >= MAX_CHUNKS) return@forEach
            val normalized = normalize(page.text)
            if (normalized.isBlank()) return@forEach

            var start = 0
            while (start < normalized.length && output.size < MAX_CHUNKS) {
                var end = minOf(start + MAX_CHUNK_CHARS, normalized.length)
                if (end < normalized.length) {
                    val boundary = normalized.lastIndexOfAny(
                        charArrayOf(' ', '\n', '\t', '\r'),
                        end
                    )
                    if (boundary > start + (MAX_CHUNK_CHARS * 0.65).toInt()) {
                        end = boundary
                    }
                }

                val body = normalized.substring(start, end).trim()
                if (body.isNotEmpty()) {
                    val ordinal = output.size
                    val label = page.pageNumber?.let { "Page $it" } ?: "Section ${ordinal + 1}"
                    output += DocumentChunkDraft(
                        ordinal = ordinal,
                        pageNumber = page.pageNumber,
                        sectionLabel = label,
                        content = body
                    )
                }

                if (end >= normalized.length) break
                var next = (end - OVERLAP_CHARS).coerceAtLeast(start + 1)
                while (next < normalized.length && !normalized[next].isWhitespace()) next++
                start = if (next < normalized.length) next + 1 else end
            }
        }
        return output
    }

    fun score(query: String, content: String): Int {
        val terms = tokenize(query)
        if (terms.isEmpty()) return 0
        val normalizedContent = content.lowercase(Locale.ROOT)
        val matched = terms.count { normalizedContent.contains(it) }
        if (matched == 0) return 0
        val phrase = query.trim().lowercase(Locale.ROOT)
        return matched * 10 + if (phrase.length >= 3 && normalizedContent.contains(phrase)) 8 else 0
    }

    fun tokenize(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 }
            .distinct()
            .toList()

    private fun normalize(value: String): String = value
        .replace("\u0000", " ")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}
