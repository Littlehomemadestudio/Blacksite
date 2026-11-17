package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficeDocumentExtractorTest {
    @Test
    fun docxExtractsParagraphsAndRetainsDocumentCitation() {
        val file = officeZip(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
        )
        try {
            val pages = OfficeDocumentExtractor.extract(file, OfficeDocumentFormat.DOCX)
            assertEquals(1, pages.size)
            assertEquals("Document body", pages.single().label)
            assertTrue(pages.single().text.contains("First paragraph"))
            assertTrue(pages.single().text.contains("Second paragraph"))
            assertEquals("Document body", DocumentTextProcessor.chunk(pages).first().sectionLabel)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pptxExtractsSlidesInNumericOrderAndRetainsSlideCitations() {
        val file = officeZip(
            "ppt/slides/slide10.xml" to slideXml("Tenth slide"),
            "ppt/slides/slide2.xml" to slideXml("Second slide")
        )
        try {
            val pages = OfficeDocumentExtractor.extract(file, OfficeDocumentFormat.PPTX)
            assertEquals(listOf("Slide 2", "Slide 10"), pages.map { it.label })
            assertTrue(pages[0].text.contains("Second slide"))
            assertTrue(pages[1].text.contains("Tenth slide"))
            assertEquals("Slide 2", DocumentTextProcessor.chunk(pages).first().sectionLabel)
        } finally {
            file.delete()
        }
    }

    @Test
    fun xlsxResolvesWorksheetNamesSharedStringsAndInlineText() {
        val file = officeZip(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Quarterly results" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                </Relationships>
            """.trimIndent(),
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Revenue</t></si><si><t>Costs</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>1200</v></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>Costs</t></is></c><c r="B2"><v>800</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent()
        )
        try {
            val pages = OfficeDocumentExtractor.extract(file, OfficeDocumentFormat.XLSX)
            assertEquals(1, pages.size)
            assertEquals("Sheet: Quarterly results", pages.single().label)
            assertTrue(pages.single().text.contains("A1: Revenue"))
            assertTrue(pages.single().text.contains("B1: 1200"))
            assertTrue(pages.single().text.contains("A2: Costs"))
            assertTrue(pages.single().text.contains("B2: 800"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun workbookRelationshipTraversalIsIgnored() {
        val file = officeZip(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Unsafe" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="../../word/document.xml"/>
                </Relationships>
            """.trimIndent(),
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Must not be read</w:t></w:r></w:p></w:body>
                </w:document>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>
            """.trimIndent()
        )
        try {
            val pages = OfficeDocumentExtractor.extract(file, OfficeDocumentFormat.XLSX)
            assertTrue(pages.isEmpty())
        } finally {
            file.delete()
        }
    }

    private fun slideXml(text: String) = """
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
               xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld>
        </p:sld>
    """.trimIndent()

    private fun officeZip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("oracle-office-", ".zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
