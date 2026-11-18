package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamPdfExporterTest {
    private val bundle = ExamBundle(
        paper = ExamPaper(
            id = "paper-1",
            title = "Grade 10 Mathematics — Midterm",
            schoolName = "North School",
            subject = "Mathematics",
            grade = "10",
            className = "10-A",
            teacherName = "Teacher",
            semester = "First",
            examDate = "2026-11-18",
            durationMinutes = 90,
            totalMarks = 10.0,
            language = "en",
            instructions = listOf("Answer every question.", "Show your working."),
            sections = listOf(
                ExamSection(
                    title = "Section A",
                    instructions = "Choose one answer.",
                    questions = listOf(
                        ExamQuestion(
                            id = "q1",
                            number = 1,
                            type = "multiple_choice",
                            prompt = "What is 2 + 2?",
                            marks = 2.0,
                            options = listOf("2", "3", "4", "5"),
                            answerLines = 0
                        ),
                        ExamQuestion(
                            id = "q2",
                            number = 2,
                            type = "short_answer",
                            prompt = "Explain an integer.",
                            marks = 3.0,
                            answerLines = 3
                        )
                    )
                ),
                ExamSection(
                    title = "Section B",
                    questions = listOf(
                        ExamQuestion(
                            id = "q3",
                            number = 3,
                            type = "calculation",
                            prompt = "Calculate 3 × 5 and show the steps.",
                            marks = 5.0,
                            answerLines = 5
                        )
                    )
                )
            )
        ),
        answerKey = listOf(
            ExamAnswerKey(
                questionId = "q1",
                expectedAnswer = "4",
                acceptedAnswers = listOf("C"),
                markingGuide = "Correct option."
            ),
            ExamAnswerKey(
                questionId = "q2",
                expectedAnswer = "A whole number, its negative, or zero.",
                markingGuide = "Award up to 3 marks for definition and examples."
            ),
            ExamAnswerKey(
                questionId = "q3",
                expectedAnswer = "15",
                markingGuide = "Award marks for method and result."
            )
        )
    )

    @Test
    fun paperOnlyPlanIncludesCandidateFieldsOptionsAndWrittenAnswerSpace() {
        val plan = ExamPdfContentPlanner.build(bundle)
        assertTrue(plan.any { it.type == ExamPdfBlockType.CANDIDATE_FIELDS })
        assertTrue(plan.any { it.type == ExamPdfBlockType.OPTION && it.text == "C. 4" })
        assertTrue(plan.any { it.type == ExamPdfBlockType.ANSWER_SPACE && it.answerLines == 3 })
        assertTrue(plan.any { it.type == ExamPdfBlockType.ANSWER_SPACE && it.answerLines == 5 })
        assertFalse(plan.any { it.type == ExamPdfBlockType.ANSWER_KEY_HEADING })
        assertFalse(plan.any { it.type == ExamPdfBlockType.ANSWER_KEY_ITEM })
    }

    @Test
    fun markingSchemeIsIncludedOnlyAfterExplicitOptIn() {
        val plan = ExamPdfContentPlanner.build(bundle, includeAnswerKey = true)
        assertTrue(plan.any { it.type == ExamPdfBlockType.ANSWER_KEY_HEADING })
        assertEquals(3, plan.count { it.type == ExamPdfBlockType.ANSWER_KEY_ITEM })
        assertTrue(plan.last().text.contains("Award marks for method and result."))
    }

    @Test
    fun fileNameSanitizationPreservesPersianAndRemovesPathSeparators() {
        assertEquals("mathematics-midterm.pdf", ExamPdfExporter.suggestedFileName("Mathematics / Midterm"))
        assertEquals("ریاضی-فصل-سه.pdf", ExamPdfExporter.suggestedFileName("ریاضی / فصل سه"))
        assertEquals("oracle-exam.pdf", ExamPdfExporter.suggestedFileName(" / "))
    }

    @Test
    fun combinedExportGetsAnExplicitMarkingSchemeSuffix() {
        assertEquals(
            "midterm-with-marking-scheme.pdf",
            ExamPdfExporter.suggestedFileName("Midterm", includeAnswerKey = true)
        )
    }
}
