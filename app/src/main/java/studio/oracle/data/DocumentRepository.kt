package studio.oracle.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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
import java.io.Writer
import java.util.Locale
import java.util.UUID

data class DocumentSearchHit(
    val document: DocumentEntity,
    val chunk: DocumentChunkEntity,
    val score: Int
)

sealed interface DocumentImportResult {
    data class Success(
        val document: DocumentEntity,
        val chunkCount: Int,
        val ocrPageCount: Int = 0,
        val ocrWarning: String? = null
    ) : DocumentImportResult
    data class Failure(val message: String) : DocumentImportResult
}

/**
 * Imports supported files into app-private storage, extracts text offline, and
 * commits document metadata + page-aware chunks in one Room transaction.
 */
class DocumentRepository(
    context: Context,
    private val dao: DocumentDao,
    private val workspaceId: String
) {
    private val appContext = context.applicationContext
    private val documentsDirectory = File(appContext.filesDir, "documents")

    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments(workspaceId)

    suspend fun importFrom(uri: Uri): DocumentImportResult = withContext(Dispatchers.IO) {
        var temporaryFile: File? = null
        var storedFile: File? = null
        var committed = false
        var ocrPageCount = 0
        var ocrWarning: String? = null
        try {
            val displayName = resolveDisplayName(uri)
            val mimeType = appContext.contentResolver.getType(uri).orEmpty()
            val format = detectFormat(displayName, mimeType)
                ?: throw DocumentImportException(
                    "Unsupported file. Supported formats: PDF, TXT, Markdown, DOCX, PPTX, and XLSX."
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
                SupportedFormat.PDF -> {
                    val selectablePages = extractPdf(destination)
                    val ocr = OnDeviceOcr.recognizeBlankPdfPages(destination, selectablePages)
                    ocrPageCount = ocr.recognizedPageCount
                    ocrWarning = buildPdfOcrWarning(ocr)
                    ocr.pages
                }
                SupportedFormat.IMAGE_JPEG, SupportedFormat.IMAGE_PNG, SupportedFormat.IMAGE_WEBP -> {
                    val text = try {
                        OnDeviceOcr.recognizeImage(destination)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw DocumentImportException("Offline OCR could not decode this image. Supported image types are JPG, PNG, and WebP.")
                    }
                    if (text.isBlank()) {
                        throw DocumentImportException(
                            "No readable Latin-script text was detected. The bundled offline OCR model does not recognize Persian/Arabic-only or handwritten text."
                        )
                    }
                    ocrPageCount = 1
                    listOf(ExtractedPage(pageNumber = null, label = "Image OCR", text = text))
                }
                SupportedFormat.TEXT, SupportedFormat.MARKDOWN -> extractText(destination)
                SupportedFormat.DOCX -> OfficeDocumentExtractor.extract(destination, OfficeDocumentFormat.DOCX)
                SupportedFormat.PPTX -> OfficeDocumentExtractor.extract(destination, OfficeDocumentFormat.PPTX)
                SupportedFormat.XLSX -> OfficeDocumentExtractor.extract(destination, OfficeDocumentFormat.XLSX)
            }
            val extractedCharacters = pages.sumOf { it.text.length }
            if (extractedCharacters == 0) {
                throw DocumentImportException(
                    when (format) {
                        SupportedFormat.PDF -> "No selectable text or readable Latin-script OCR text was found. Handwriting and Persian/Arabic-only scans are not recognized by the bundled offline OCR model."
                        SupportedFormat.IMAGE_JPEG, SupportedFormat.IMAGE_PNG, SupportedFormat.IMAGE_WEBP ->
                            "No readable Latin-script text was found in this image. Handwriting and Persian/Arabic-only text are not recognized by the bundled offline OCR model."
                        SupportedFormat.DOCX, SupportedFormat.PPTX, SupportedFormat.XLSX -> "No readable text was found in this Office file. Embedded images, charts, and scanned content are not OCR-extracted."
                        else -> "This file contains no readable text."
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
                importedAt = System.currentTimeMillis(),
                workspaceId = workspaceId
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
            DocumentImportResult.Success(entity, chunks.size, ocrPageCount, ocrWarning)
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
            val terms = DocumentTextProcessor.tokenize(query).take(8)
            if (terms.isEmpty()) return@withContext emptyList()
            val documentsById = dao.documentsSnapshot(workspaceId).associateBy { it.id }
            terms.flatMap { dao.chunksMatching(workspaceId, it) }
                .distinctBy { it.id }
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

    private fun buildPdfOcrWarning(result: PdfOcrResult): String? {
        val warnings = buildList {
            if (result.skippedPageCount > 0) {
                add("OCR is capped at ${OcrImportPolicy.MAX_PDF_OCR_PAGES} image-only pages per import; ${result.skippedPageCount} page(s) were skipped.")
            }
            if (result.failedPageCount > 0) {
                add("OCR could not process ${result.failedPageCount} scanned page(s); selectable text and successful OCR pages remain indexed.")
            }
            if (result.unreadablePageCount > 0) {
                add("No Latin-script text was detected on ${result.unreadablePageCount} image-only page(s). Handwriting and Persian/Arabic-only scans are not supported by the bundled model.")
            }
        }
        return warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
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
            extension == "docx" || mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true) -> SupportedFormat.DOCX
            extension == "pptx" || mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ignoreCase = true) -> SupportedFormat.PPTX
            extension == "xlsx" || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ignoreCase = true) -> SupportedFormat.XLSX
            extension in setOf("jpg", "jpeg") || mimeType.equals("image/jpeg", ignoreCase = true) || mimeType.equals("image/jpg", ignoreCase = true) -> SupportedFormat.IMAGE_JPEG
            extension == "png" || mimeType.equals("image/png", ignoreCase = true) -> SupportedFormat.IMAGE_PNG
            extension == "webp" || mimeType.equals("image/webp", ignoreCase = true) -> SupportedFormat.IMAGE_WEBP
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
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly(MAX_PDF_SCRATCH_BYTES)).use { pdf ->
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
                    LimitedTextWriter(remaining).use { writer ->
                        stripper.writeText(pdf, writer)
                        writer.content()
                    }.also { remaining -= it.length }
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


    private class LimitedTextWriter(limit: Int) : Writer() {
        private val output = StringBuilder()
        private var remaining = limit

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val count = minOf(length, remaining)
            if (count > 0) {
                output.append(buffer, offset, count)
                remaining -= count
            }
        }

        override fun flush() = Unit
        override fun close() = Unit

        fun content(): String = output.toString()
    }

    private fun sanitizeFileName(name: String, extension: String): String {
        val safe = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().take(120).ifBlank { "Imported document.$extension" }
        return safe.substringBeforeLast('.', safe).ifBlank { "Imported document" } + ".$extension"
    }

    private enum class SupportedFormat(val extension: String, val mimeType: String) {
        PDF("pdf", "application/pdf"),
        TEXT("txt", "text/plain"),
        MARKDOWN("md", "text/markdown"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        IMAGE_JPEG("jpg", "image/jpeg"),
        IMAGE_PNG("png", "image/png"),
        IMAGE_WEBP("webp", "image/webp")
    }

    private class DocumentImportException(message: String) : Exception(message)

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L
        const val MAX_EXTRACTED_CHARACTERS = 1_500_000
        const val MAX_PDF_PAGES = 400
        const val MAX_PDF_SCRATCH_BYTES = 100L * 1024L * 1024L
    }
}
