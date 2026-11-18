package studio.oracle.data

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.OutputStream
import java.util.Locale

enum class ExamPdfBlockType {
    SCHOOL, TITLE, METADATA, CANDIDATE_FIELDS, INSTRUCTIONS_HEADING, INSTRUCTION,
    SECTION_HEADING, SECTION_INSTRUCTION, QUESTION, OPTION, ANSWER_SPACE,
    ANSWER_KEY_HEADING, ANSWER_KEY_ITEM
}

data class ExamPdfBlock(
    val type: ExamPdfBlockType,
    val text: String,
    val answerLines: Int = 0
)

/** Builds a deterministic representation of the printable paper. The marking scheme is opt-in. */
object ExamPdfContentPlanner {
    fun build(bundle: ExamBundle, includeAnswerKey: Boolean = false): List<ExamPdfBlock> {
        val paper = bundle.paper
        val rtl = paper.language.startsWith("fa", true) || paper.language.startsWith("ar", true)
        return buildList {
            if (paper.schoolName.isNotBlank()) add(ExamPdfBlock(ExamPdfBlockType.SCHOOL, paper.schoolName))
            add(ExamPdfBlock(ExamPdfBlockType.TITLE, paper.title))

            val metadataRows = if (rtl) {
                listOf(
                    "درس: ${{paper.subject}    پایه: ${{paper.grade}    کلاس: ${{paper.className}",
                    "مدت: ${{paper.durationMinutes} دقیقه    مجموع بارم: ${{paper.totalMarks.toPdfMarks()}    تاریخ: ${{paper.examDate}",
                    "دبیر: ${{paper.teacherName}    نیمسال: ${{paper.semester}"
                )
            } else {
                listOf(
                    "Subject: ${{paper.subject}    Grade: ${{paper.grade}    Class: ${{paper.className}",
                    "Duration: ${{paper.durationMinutes} min    Total marks: ${{paper.totalMarks.toPdfMarks()}    Date: ${{paper.examDate}",
                    "Teacher: ${{paper.teacherName}    Term: ${{paper.semester}"
                )
            }
            metadataRows.forEach { add(ExamPdfBlock(ExamPdfBlockType.METADATA, it)) }

            val candidateFields = if (rtl) {
                "نام و نام خانوادگی: ____________________    شماره دانش‌آموزی: ____________    کلاس: ${{paper.className}"
            } else {
                "Student name: __________________________    Student ID: ______________    Class: ${{paper.className}"
            }
            add(ExamPdfBlock(ExamPdfBlockType.CANDIDATE_FIELDS, candidateFields))

            if (paper.instructions.isNotEmpty()) {
                add(ExamPdfBlock(ExamPdfBlockType.INSTRUCTIONS_HEADING, if (rtl) "دستورالعمل" else "CANDIDATE INSTRUCTIONS"))
                paper.instructions.forEachIndexed { index, instruction ->
                    add(ExamPdfBlock(ExamPdfBlockType.INSTRUCTION, "${index + 1}. $instruction"))
                }
            }

            paper.sections.forEach { section ->
                add(ExamPdfBlock(ExamPdfBlockType.SECTION_HEADING, section.title))
                if (section.instructions.isNotBlank()) {
                    add(ExamPdfBlock(ExamPdfBlockType.SECTION_INSTRUCTION, section.instructions))
                }
                section.questions.forEach { question ->
                    val marksLabel = if (rtl) "نمره" else "marks"
                    add(
                        ExamPdfBlock(
                            ExamPdfBlockType.QUESTION,
                            "${question.number}. ${question.prompt}    [${question.marks.toPdfMarks()} $marksLabel]"
                        )
                    )
                    question.options.forEachIndexed { index, option ->
                        val optionLabel = ('A'.code + index).toChar()
                        add(ExamPdfBlock(ExamPdfBlockType.OPTION, "$optionLabel. $option"))
                    }
                    if (question.options.isEmpty() && question.answerLines > 0) {
                        add(ExamPdfBlock(ExamPdfBlockType.ANSWER_SPACE, "", question.answerLines.coerceIn(0, 12)))
                    }
                }
            }

            if (includeAnswerKey && bundle.answerKey.isNotEmpty()) {
                add(ExamPdfBlock(ExamPdfBlockType.ANSWER_KEY_HEADING, if (rtl) "پاسخ‌نامه و راهنمای تصحیح" else "MARKING SCHEME"))
                val questionNumbers = paper.questions.associate { it.id to it.number }
                bundle.answerKey.forEach { key ->
                    val number = questionNumbers[key.questionId]?.toString() ?: key.questionId
                    val answerLabel = if (rtl) "پاسخ مورد انتظار" else "Expected answer"
                    val acceptedLabel = if (rtl) "پاسخ‌های پذیرفته‌شده" else "Accepted answers"
                    val guideLabel = if (rtl) "راهنمای تصحیح" else "Marking guide"
                    val parts = mutableListOf("Q$number · $answerLabel: ${key.expectedAnswer}")
                    if (key.acceptedAnswers.isNotEmpty()) parts += "$acceptedLabel: ${key.acceptedAnswers.joinToString(", ")}"
                    if (key.markingGuide.isNotBlank()) parts += "$guideLabel: ${key.markingGuide}"
                    add(ExamPdfBlock(ExamPdfBlockType.ANSWER_KEY_ITEM, parts.joinToString("\n")))
                }
            }
        }
    }
}

object ExamPdfExporter {
    fun suggestedFileName(title: String, includeAnswerKey: Boolean = false): String {
        val safeTitle = title.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "oracle-exam" }
        val suffix = if (includeAnswerKey) "-with-marking-scheme" else ""
        return "$safeTitle$suffix.pdf"
    }

    /** Writes a paginated A4 PDF. The caller owns and closes the supplied stream. */
    fun write(bundle: ExamBundle, output: OutputStream, includeAnswerKey: Boolean = false) {
        require(bundle.paper.questions.isNotEmpty()) { "An examination needs at least one question." }
        val rtl = bundle.paper.language.startsWith("fa", true) || bundle.paper.language.startsWith("ar", true)
        val document = PdfDocument()
        try {
            val composer = ExamPdfPageComposer(document, rtl)
            ExamPdfContentPlanner.build(bundle, includeAnswerKey).forEach(composer::draw)
            composer.finish()
            document.writeTo(output)
            output.flush()
        } finally {
            document.close()
        }
    }
}

private class ExamPdfPageComposer(
    private val document: PdfDocument,
    private val rtl: Boolean
) {
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var cursorY = TOP_MARGIN.toFloat()

    fun draw(block: ExamPdfBlock) {
        when (block.type) {
            ExamPdfBlockType.SCHOOL -> paragraph(block.text, 10f, bold = true, alignment = Layout.Alignment.ALIGN_CENTER, gapAfter = 5)
            ExamPdfBlockType.TITLE -> paragraph(block.text, 18f, bold = true, alignment = Layout.Alignment.ALIGN_CENTER, gapAfter = 12)
            ExamPdfBlockType.METADATA -> paragraph(block.text, 8.5f, gapAfter = 5)
            ExamPdfBlockType.CANDIDATE_FIELDS -> paragraph(block.text, 8.5f, gapAfter = 10)
            ExamPdfBlockType.INSTRUCTIONS_HEADING -> paragraph(block.text, 8f, bold = true, gapAfter = 3)
            ExamPdfBlockType.INSTRUCTION -> paragraph(block.text, 8.5f, indent = 8, gapAfter = 3)
            ExamPdfBlockType.SECTION_HEADING, ExamPdfBlockType.ANSWER_KEY_HEADING -> sectionHeading(block.text)
            ExamPdfBlockType.SECTION_INSTRUCTION -> paragraph(block.text, 8f, italic = true, indent = 8, gapAfter = 5)
            ExamPdfBlockType.QUESTION -> paragraph(block.text, 9.5f, bold = true, gapAfter = 4)
            ExamPdfBlockType.OPTION -> paragraph(block.text, 8.5f, indent = 18, gapAfter = 2)
            ExamPdfBlockType.ANSWER_SPACE -> answerLines(block.answerLines)
            ExamPdfBlockType.ANSWER_KEY_ITEM -> paragraph(block.text, 8.5f, indent = 8, gapAfter = 9)
        }
    }

    fun finish() = finishPage()

    private fun paragraph(
        text: String,
        size: Float,
        bold: Boolean = false,
        italic: Boolean = false,
        indent: Int = 0,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        gapAfter: Int = 7
    ) {
        if (text.isBlank()) return
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create("sans-serif", style)
            color = android.graphics.Color.rgb(28, 31, 35)
        }
        val width = (PAGE_WIDTH - PAGE_MARGIN * 2 - indent).coerceAtLeast(80)
        val direction = if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setTextDirection(direction)
            .setIncludePad(false)
            .setLineSpacing(2f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
        drawLayout(layout, indent, gapAfter)
    }

    private fun drawLayout(layout: StaticLayout, indent: Int, gapAfter: Int) {
        var lineIndex = 0
        while (lineIndex < layout.lineCount) {
            if (page == null) startPage()
            val startTop = layout.getLineTop(lineIndex)
            val availableHeight = (CONTENT_BOTTOM - cursorY).toInt()
            val firstLineHeight = (layout.getLineBottom(lineIndex) - startTop).coerceAtLeast(1)
            if (availableHeight < firstLineHeight) {
                finishPage()
                startPage()
                continue
            }
            var endLine = lineIndex
            while (endLine < layout.lineCount && layout.getLineBottom(endLine) - startTop <= availableHeight) endLine++
            if (endLine == lineIndex) {
                finishPage()
                startPage()
                continue
            }
            val endBottom = layout.getLineBottom(endLine - 1)
            val canvas = page!!.canvas
            canvas.save()
            canvas.translate((PAGE_MARGIN + indent).toFloat(), cursorY - startTop.toFloat())
            canvas.clipRect(
                0f,
                startTop.toFloat(),
                (PAGE_WIDTH - PAGE_MARGIN * 2 - indent).toFloat(),
                endBottom.toFloat()
            )
            layout.draw(canvas)
            canvas.restore()
            cursorY += (endBottom - startTop).toFloat()
            lineIndex = endLine
            if (lineIndex < layout.lineCount) {
                finishPage()
                startPage()
            }
        }
        cursorY += gapAfter
    }

    private fun sectionHeading(text: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = android.graphics.Color.rgb(24, 30, 37)
        }
        val width = PAGE_WIDTH - PAGE_MARGIN * 2 - 16
        val direction = if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(direction)
            .setIncludePad(false)
            .build()
        val blockHeight = layout.height + 10
        ensureSpace(blockHeight.toFloat())
        val canvas = page!!.canvas
        val top = cursorY
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(238, 241, 244)
            style = Paint.Style.FILL
        }
        canvas.drawRect(PAGE_MARGIN.toFloat(), top, (PAGE_WIDTH - PAGE_MARGIN).toFloat(), top + blockHeight, background)
        canvas.save()
        canvas.translate((PAGE_MARGIN + 8).toFloat(), top + 5f)
        layout.draw(canvas)
        canvas.restore()
        cursorY += blockHeight + 7f
    }

    private fun answerLines(count: Int) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(178, 184, 190)
            strokeWidth = 0.65f
        }
        repeat(count.coerceIn(0, 12)) {
            ensureSpace(20f)
            val y = cursorY + 15f
            page!!.canvas.drawLine(
                (PAGE_MARGIN + 4).toFloat(),
                y,
                (PAGE_WIDTH - PAGE_MARGIN - 4).toFloat(),
                y,
                linePaint
            )
            cursorY += 20f
        }
        cursorY += 5f
    }

    private fun ensureSpace(requiredHeight: Float) {
        if (page == null) startPage()
        if (cursorY + requiredHeight > CONTENT_BOTTOM) {
            finishPage()
            startPage()
        }
    }

    private fun startPage() {
        pageNumber += 1
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        page = document.startPage(info)
        val canvas = page!!.canvas
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(105, 113, 121)
            textSize = 7f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("ORACLE STUDIO", PAGE_MARGIN.toFloat(), 25f, headerPaint)
        val headerLabel = "EXAMINATION DOCUMENT"
        canvas.drawText(headerLabel, PAGE_WIDTH - PAGE_MARGIN - headerPaint.measureText(headerLabel), 25f, headerPaint)
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(205, 210, 215)
            strokeWidth = 0.7f
        }
        canvas.drawLine(PAGE_MARGIN.toFloat(), 34f, (PAGE_WIDTH - PAGE_MARGIN).toFloat(), 34f, rulePaint)
        cursorY = TOP_MARGIN.toFloat()
    }

    private fun finishPage() {
        val activePage = page ?: return
        val canvas: Canvas = activePage.canvas
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(205, 210, 215)
            strokeWidth = 0.7f
        }
        val footerY = (PAGE_HEIGHT - 34).toFloat()
        canvas.drawLine(PAGE_MARGIN.toFloat(), footerY, (PAGE_WIDTH - PAGE_MARGIN).toFloat(), footerY, rulePaint)
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(115, 122, 130)
            textSize = 7f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("ORACLE STUDIO  ·  EXAMINATION", PAGE_MARGIN.toFloat(), (PAGE_HEIGHT - 18).toFloat(), footerPaint)
        val pageLabel = "PAGE $pageNumber"
        canvas.drawText(pageLabel, PAGE_WIDTH - PAGE_MARGIN - footerPaint.measureText(pageLabel), (PAGE_HEIGHT - 18).toFloat(), footerPaint)
        document.finishPage(activePage)
        page = null
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val PAGE_MARGIN = 42
        const val TOP_MARGIN = 52
        const val CONTENT_BOTTOM = PAGE_HEIGHT - 52
    }
}

private fun Double.toPdfMarks(): String =
    if (this % 1.0 == 0.0) toInt().toString() else String.format(Locale.US, "%.1f", this)
