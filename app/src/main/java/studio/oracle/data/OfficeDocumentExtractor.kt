package studio.oracle.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

enum class OfficeDocumentFormat { DOCX, PPTX, XLSX }

/**
 * Bounded, offline extraction for Office Open XML files.
 * Archive entries are read in place and never extracted to disk. Macros are
 * never executed; images, charts, comments and tracked-change metadata are not OCR-parsed.
 */
object OfficeDocumentExtractor {
    private const val MAX_XML_ENTRY_BYTES = 12 * 1024 * 1024
    private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 80L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 2_000
    private const val MAX_SLIDES = 400
    private const val MAX_SHEETS = 100
    const val MAX_EXTRACTED_CHARACTERS = 1_500_000
    private const val RELATIONSHIP_NAMESPACE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun extract(file: File, format: OfficeDocumentFormat): List<ExtractedPage> =
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.size > MAX_ARCHIVE_ENTRIES) {
                throw IOException("Office archive contains too many entries.")
            }
            val uncompressedBytes = entries.fold(0L) { total, entry ->
                total + entry.size.coerceAtLeast(0L)
            }
            if (uncompressedBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                throw IOException("Office archive expands beyond the 80 MB safety limit.")
            }
            when (format) {
                OfficeDocumentFormat.DOCX -> extractDocx(zip)
                OfficeDocumentFormat.PPTX -> extractPptx(zip, entries.map { it.name })
                OfficeDocumentFormat.XLSX -> extractXlsx(zip, entries.map { it.name })
            }
        }

    private fun extractDocx(zip: ZipFile): List<ExtractedPage> {
        val body = readParagraphs(zip, "word/document.xml", "p", MAX_EXTRACTED_CHARACTERS)
        return if (body.isBlank()) emptyList()
        else listOf(ExtractedPage(null, "Document body", body))
    }

    private fun extractPptx(zip: ZipFile, entryNames: List<String>): List<ExtractedPage> {
        val slides = entryNames.mapNotNull { name ->
            SLIDE_ENTRY.matchEntire(name)?.let {
                it.groupValues[1].toIntOrNull()?.let { number -> number to name }
            }
        }.sortedBy { it.first }
        if (slides.isEmpty()) throw IOException("Presentation contains no readable slides.")
        if (slides.size > MAX_SLIDES) throw IOException("Presentation exceeds the 400-slide limit.")

        val pages = mutableListOf<ExtractedPage>()
        var remaining = MAX_EXTRACTED_CHARACTERS
        for ((number, path) in slides) {
            if (remaining <= 0) break
            val text = readParagraphs(zip, path, "p", remaining)
            if (text.isNotBlank()) {
                pages += ExtractedPage(null, "Slide $number", text)
                remaining -= text.length
            }
        }
        return pages
    }

    private fun extractXlsx(zip: ZipFile, entryNames: List<String>): List<ExtractedPage> {
        val shared = if (zip.getEntry("xl/sharedStrings.xml") != null) {
            readSharedStrings(zip)
        } else {
            emptyList()
        }
        val sheets = resolveSheets(zip, entryNames)
        if (sheets.isEmpty()) throw IOException("Workbook contains no readable worksheets.")
        if (sheets.size > MAX_SHEETS) throw IOException("Workbook exceeds the 100-sheet limit.")

        val pages = mutableListOf<ExtractedPage>()
        var remaining = MAX_EXTRACTED_CHARACTERS
        for ((index, sheet) in sheets.withIndex()) {
            if (remaining <= 0) break
            val text = readWorksheet(zip, sheet.path, shared, remaining)
            if (text.isNotBlank()) {
                val name = sheet.name.take(80).ifBlank { "Sheet ${index + 1}" }
                pages += ExtractedPage(null, "Sheet: $name", text)
                remaining -= text.length
            }
        }
        return pages
    }

    private fun readParagraphs(zip: ZipFile, path: String, paragraphTag: String, limit: Int): String =
        withParser(zip, path) { parser ->
            val output = StringBuilder()
            var paragraph: StringBuilder? = null
            var readingText = false
            while (parser.next() != XmlPullParser.END_DOCUMENT && output.length < limit) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        paragraphTag -> if (paragraph == null) paragraph = StringBuilder()
                        "t" -> if (paragraph != null) readingText = true
                        "tab" -> paragraph?.append('\t')
                        "br" -> paragraph?.append('\n')
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (readingText && paragraph != null) {
                        appendLimited(paragraph!!, parser.text.orEmpty(), limit)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "t" -> readingText = false
                        paragraphTag -> {
                            val content = paragraph?.toString().orEmpty().trim()
                            if (content.isNotEmpty()) {
                                appendLimited(output, content, limit)
                                if (output.length < limit) output.append('\n')
                            }
                            paragraph = null
                            readingText = false
                        }
                    }
                }
            }
            output.toString().trim()
        }

    private fun readSharedStrings(zip: ZipFile): List<String> =
        withParser(zip, "xl/sharedStrings.xml") { parser ->
            val strings = mutableListOf<String>()
            var inItem = false
            var inText = false
            var inPhonetic = false
            var current = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "si" -> { inItem = true; current = StringBuilder() }
                        "rPh" -> inPhonetic = true
                        "t" -> if (inItem && !inPhonetic) inText = true
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem && inText && !inPhonetic) {
                        appendLimited(current, parser.text.orEmpty(), MAX_EXTRACTED_CHARACTERS)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "t" -> inText = false
                        "rPh" -> inPhonetic = false
                        "si" -> { strings += current.toString(); inItem = false; inText = false }
                    }
                }
                if (strings.size >= 200_000) break
            }
            strings
        }

    private fun resolveSheets(zip: ZipFile, entryNames: List<String>): List<WorkbookSheet> {
        val workbookPath = "xl/workbook.xml"
        val relationshipsPath = "xl/_rels/workbook.xml.rels"
        if (zip.getEntry(workbookPath) != null && zip.getEntry(relationshipsPath) != null) {
            val relationships = withParser(zip, relationshipsPath) { parser ->
                buildMap {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                            val id = attribute(parser, "Id")
                            val target = attribute(parser, "Target")?.let(::resolveRelationshipTarget)
                            if (!id.isNullOrBlank() && target != null) put(id, target)
                        }
                    }
                }
            }
            val resolved = withParser(zip, workbookPath) { parser ->
                buildList {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                            val name = attribute(parser, "name").orEmpty()
                            val relationId = parser.getAttributeValue(RELATIONSHIP_NAMESPACE, "id")
                            val path = relationId?.let(relationships::get)
                            if (path != null && zip.getEntry(path) != null) {
                                add(WorkbookSheet(name.ifBlank { "Sheet ${size + 1}" }, path))
                            }
                        }
                    }
                }
            }
            if (resolved.isNotEmpty()) return resolved
        }

        return entryNames.mapNotNull { name ->
            WORKSHEET_ENTRY.matchEntire(name)?.let {
                it.groupValues[1].toIntOrNull()?.let { number -> number to name }
            }
        }.sortedBy { it.first }.map { (number, path) -> WorkbookSheet("Sheet $number", path) }
    }

    private fun resolveRelationshipTarget(raw: String): String? {
        val target = raw.trim().replace('\\', '/').removePrefix("/")
        if (target.isBlank()) return null
        val parts = target.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        val resolved = if (target.startsWith("xl/")) target else "xl/$target"
        return resolved.takeIf { it.startsWith("xl/") }
    }

    private fun readWorksheet(zip: ZipFile, path: String, shared: List<String>, limit: Int): String =
        withParser(zip, path) { parser ->
            val output = StringBuilder()
            val rowCells = mutableListOf<String>()
            var rowNumber = ""
            var cellReference = ""
            var cellType = ""
            var cellValue = StringBuilder()
            var readingValue = false
            while (parser.next() != XmlPullParser.END_DOCUMENT && output.length < limit) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> { rowNumber = attribute(parser, "r").orEmpty(); rowCells.clear() }
                        "c" -> {
                            cellReference = attribute(parser, "r").orEmpty()
                            cellType = attribute(parser, "t").orEmpty()
                            cellValue = StringBuilder()
                        }
                        "v", "t" -> if (cellReference.isNotBlank()) readingValue = true
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (readingValue && cellReference.isNotBlank()) {
                        appendLimited(cellValue, parser.text.orEmpty(), 8_000)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v", "t" -> readingValue = false
                        "c" -> {
                            val raw = cellValue.toString()
                            val value = when (cellType) {
                                "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                                "b" -> if (raw == "1") "TRUE" else if (raw == "0") "FALSE" else raw
                                else -> raw
                            }
                            if (value.isNotBlank() && cellReference.isNotBlank()) {
                                rowCells += "$cellReference: ${value.take(5_000)}"
                            }
                            cellReference = ""
                            cellType = ""
                            readingValue = false
                        }
                        "row" -> if (rowCells.isNotEmpty()) {
                            val prefix = rowNumber.takeIf { it.isNotBlank() }?.let { "Row $it: " }.orEmpty()
                            appendLimited(output, prefix + rowCells.joinToString("  |  "), limit)
                            if (output.length < limit) output.append('\n')
                        }
                    }
                }
            }
            output.toString().trim()
        }

    private fun <T> withParser(zip: ZipFile, path: String, block: (XmlPullParser) -> T): T {
        val bytes = readEntry(zip, path)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
        return block(parser)
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: throw IOException("Office document is missing $path.")
        if (entry.size > MAX_XML_ENTRY_BYTES) throw IOException("Office document contains an oversized XML part.")
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_XML_ENTRY_BYTES) throw IOException("Office document contains an oversized XML part.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun attribute(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name)
            ?: (0 until parser.attributeCount)
                .firstOrNull { parser.getAttributeName(it).substringAfter(':') == name }
                ?.let { parser.getAttributeValue(it) }

    private fun appendLimited(target: StringBuilder, value: String, limit: Int) {
        val count = (limit - target.length).coerceAtLeast(0).coerceAtMost(value.length)
        if (count > 0) target.append(value, 0, count)
    }

    private data class WorkbookSheet(val name: String, val path: String)
    private val SLIDE_ENTRY = Regex("""ppt/slides/slide(\d+)\.xml""")
    private val WORKSHEET_ENTRY = Regex("""xl/worksheets/sheet(\d+)\.xml""")
}
