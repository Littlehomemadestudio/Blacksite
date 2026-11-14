package studio.oracle.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.DocumentDao
import studio.oracle.data.local.DocumentEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class DocumentSearchHit(
    val document: DocumentEntity,
    val chunk: DocumentChunkEntity,
    val score: Int
)

sealed interface DocumentImportResult {
    data class Success(val document: DocumentEntity, val chunkCount: Int) : DocumentImportResult
    data class Failure(val message: String) : DocumentImportResult
}

/**
 * Imports supported files into app-private storage, extracts text offline, and
 * commits document metadata + page-aware chunks in one Room transaction.
 */
class DocumentRepository(
    context: Context,
    private val dao: DocumentDao
) {
    private val appContext = context.applicationContext
    private val documentsDirectory = File(appContext.filesDir, "documents")

    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments()

    suspend fun importFrom(uri: Uri): DocumentImportResult = withContext(Dispatchers.IO) {
        var temporaryFile: File? = null
        var storedFile: File? = null
        var committed = false
        try {
            val displayName = resolveDisplayName(uri)
            val mimeType = appContext.contentResolver.getType(uri).orEmpty()
            val format = detectFormat(displayName, mimeType)
                ?: throw DocumentImportException(
                    "Unsupported file. ORACLE currently imports PDF, TXT, and Markdown only."
                )

            if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
                throw DocumentImportException("Could not create the private document library.")
            }

            val id = UUID.randomUUID().toString()
            val temp = File(documentsDirectory, ".$id.importing")
            val destination = File(documentsDirectory, "$id.${format.extension}")
            temporaryFile = temp
            storedFile = destination

            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw DocumentImportException("Android could not open this document.")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FILE_BYTES) {
                            throw DocumentImportException("File exceeds the 20 MB import limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }

            val pages = when (format) {
                SupportedFormat.PDF -> extractPdf(destination)
                SupportedFormat.TEXT, SupportedFormat.MARKDOWN -> extractText(destination)
            }
            val extractedCharacters = pages.sumOf { it.text.length }
            if (extractedCharacters == 0) {
                throw DocumentImportException(
                    if (format == SupportedFormat.PDF) {
                        "No selectable text was found. Scanned/image-only PDFs need OCR, which is not available yet."
                    } else {
                        "This file contains no readable text."
                    }
                )
            }

            val drafts = DocumentTextProcessor.chunk(pages)
            if (drafts.isEmpty()) throw DocumentImportException("No searchable text could be indexed.")
            val safeName = sanitizeFileName(displayName, format.extension)
            val title = safeName.substringBeforeLast('.', safeName).ifBlank { "Imported document" }
            val entity = DocumentEntity(
                id = id,
                title = title,
                fileName = safeName,
                mimeType = format.mimeType,
                storagePath = "documents/$id.${format.extension}",
                sizeBytes = destination.length(),
                pageCount = pages.size,
                extractedCharacters = extractedCharacters,
                importedAt = System.currentTimeMillis()
            )
            val chunks = drafts.map { draft ->
                DocumentChunkEntity(
                    documentId = id,
                    ordinal = draft.ordinal,
                    pageNumber = draft.pageNumber,
                    sectionLabel = draft.sectionLabel,
                    content = draft.content
                )
            }

            dao.saveDocumentWithChunks(entity, chunks)
            committed = true
            DocumentImportResult.Success(entity, chunks.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DocumentImportException) {
            DocumentImportResult.Failure(error.message ?: "Document import failed.")
        } catch (_: InvalidPasswordException) {
            DocumentImportResult.Failure("Password-protected PDFs are not supported yet.")
        } catch (_: IOException) {
            DocumentImportResult.Failure("Could not read this file. Check that it is available and try again.")
        } catch (_: Exception) {
            DocumentImportResult.Failure("Document import failed. The file may be damaged or unsupported.")
        } finally {
            temporaryFile?.delete()
            if (!committed) storedFile?.delete()
        }
    }

    suspend fun chunksFor(documentId: String): List<DocumentChunkEntity> =
        withContext(Dispatchers.IO) { dao.chunksForDocument(documentId) }

    suspend fun search(query: String, limit: Int = 12): List<DocumentSearchHit> =
        withContext(Dispatchers.IO) {
            val terms = DocumentTextProcessor.tokenize(query)
            if (terms.isEmpty()) return@withContext emptyList()
            val documentsById = dao.documentsSnapshot().associateBy { it.id }
            dao.allChunks()
                .mapNotNull { chunk ->
                    val document = documentsById[chunk.documentId] ?: return@mapNotNull null
                    val score = DocumentTextProcessor.score(query, chunk.content)
                    if (score == 0) null else DocumentSearchHit(document, chunk, score)
                }
                .sortedWith(
                    compareByDescending<DocumentSearchHit> { it.score }
                        .thenByDescending { it.document.importedAt }
                        .thenBy { it.chunk.ordinal }
                )
                .take(limit.coerceIn(1, 50))
        }

    suspend fun delete(documentId: String) = withContext(Dispatchers.IO) {
        val document = dao.findDocument(documentId) ?: return@withContext
        dao.deleteDocument(documentId)
        val file = File(appContext.filesDir, document.storagePath)
        val parent = file.canonicalFile.parentFile
        if (parent == documentsDirectory.canonicalFile) file.delete()
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = appContext.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Imported document"
    }

    private fun detectFormat(name: String, mimeType: String): SupportedFormat? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "pdf" || mimeType.equals("application/pdf", ignoreCase = true) ->
                SupportedFormat.PDF
            extension == "txt" || mimeType.equals("text/plain", ignoreCase = true) ->
                SupportedFormat.TEXT
            extension in setOf("md", "markdown") ||
                mimeType.equals("text/markdown", ignoreCase = true) ||
                mimeType.equals("text/x-markdown", ignoreCase = true) ->
                SupportedFormat.MARKDOWN
            else -> null
        }
    }

    private fun extractText(file: File): List<ExtractedPage> {
        val bytes = file.readBytes()
        if (bytes.take(4_096).any { it == 0.toByte() }) {
            throw DocumentImportException("Binary files are not supported as plain text.")
        }
        val text = bytes.toString(Charsets.UTF_8).take(MAX_EXTRACTED_CHARACTERS)
        return listOf(ExtractedPage(pageNumber = null, label = "Document text", text = text))
    }

    private fun extractPdf(file: File): List<ExtractedPage> {
        PDFBoxResourceLoader.init(appContext)
        PDDocument.load(file).use { pdf ->
            if (pdf.numberOfPages > MAX_PDF_PAGES) {
                throw DocumentImportException("PDF exceeds the 400-page import limit.")
            }
            val output = mutableListOf<ExtractedPage>()
            var remaining = MAX_EXTRACTED_CHARACTERS
            for (page in 1..pdf.numberOfPages) {
                val pageText = if (remaining > 0) {
                    val stripper = PDFTextStripper().apply {
                        startPage = page
                        endPage = page
                        sortByPosition = true
                    }
                    stripper.getText(pdf).take(remaining).also { remaining -= it.length }
                } else {
                    ""
                }
                output += ExtractedPage(
                    pageNumber = page,
                    label = "Page $page",
                    text = pageText
                )
            }
            return output
        }
    }

    private fun sanitizeFileName(name: String, extension: String): String {
        val safe = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().take(120).ifBlank { "Imported document.$extension" }
        return safe.substringBeforeLast('.', safe).ifBlank { "Imported document" } + ".$extension"
    }

    private enum class SupportedFormat(val extension: String, val mimeType: String) {
        PDF("pdf", "application/pdf"),
        TEXT("txt", "text/plain"),
        MARKDOWN("md", "text/markdown")
    }

    private class DocumentImportException(message: String) : Exception(message)

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L
        const val MAX_EXTRACTED_CHARACTERS = 1_500_000
        const val MAX_PDF_PAGES = 400
    }
}
