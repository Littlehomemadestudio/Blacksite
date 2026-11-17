package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamPaperCodecTest {
    private val validPaper = """
        {
          "title":"Grade 10 Mathematics — Midterm",
          "schoolName":"North School",
          "subject":"Mathematics",
          "grade":"10",
          "className":"10-A",
          "teacherName":"Teacher",
          "semester":"First",
          "examDate":"2026-11-17",
          "durationMinutes":90,
          "totalMarks":10,
          "language":"en",
          "instructions":["Answer every question.","Show your working."],
          "sections":[
            {"title":"Section A","instructions":"Choose one answer.","questions":[
              {"id":"q1","number":1,"type":"multiple_choice","prompt":"What is 2 + 2?","marks":2,"options":["2","3","4","5"],"topic":"Integers","difficulty":"easy","cognitiveLevel":"remember","answerLines":0},
              {"id":"q2","number":2,"type":"short_answer","prompt":"Explain an integer.","marks":3,"topic":"Integers","difficulty":"medium","cognitiveLevel":"understand","answerLines":3}
            ]},
            {"title":"Section B","questions":[
              {"id":"q3","number":3,"type":"calculation","prompt":"Calculate 3 × 5 and show the steps.","marks":5,"topic":"Arithmetic","difficulty":"medium","cognitiveLevel":"apply","answerLines":5}
            ]}
          ],
          "answerKey":[
            {"questionId":"q1","expectedAnswer":"4","acceptedAnswers":["C"],"markingGuide":"Correct option."},
            {"questionId":"q2","expectedAnswer":"A whole number, its negative, or zero.","markingGuide":"Award up to 3 marks for definition and examples."},
            {"questionId":"q3","expectedAnswer":"15","markingGuide":"Award marks for method and result."}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesStructuredPaperAndMarkingScheme() {
        val bundle = ExamPaperCodec.parseBundle(validPaper, requireAnswerKey = true)
        assertEquals("Mathematics", bundle.paper.subject)
        assertEquals(3, bundle.paper.questionCount)
        assertEquals(10.0, bundle.paper.totalMarks, 0.001)
        assertEquals("Section B", bundle.paper.sections[1].title)
        assertEquals(3, bundle.answerKey.size)
    }

    @Test
    fun rejectsIncorrectDeclaredMarkTotal() {
        val invalid = validPaper.replace("\"totalMarks\":10", "\"totalMarks\":20")
        val result = runCatching { ExamPaperCodec.parseBundle(invalid) }
        assertTrue(result.isFailure)
    }

    @Test
    fun objectiveGradingLeavesWrittenQuestionsForManualReview() {
        val bundle = ExamPaperCodec.parseBundle(validPaper, requireAnswerKey = true)
        val grade = ExamPaperCodec.gradeObjective(bundle, mapOf("q1" to "C", "q2" to "An integer is..."))
        assertEquals(2.0, grade.autoEarned, 0.001)
        assertEquals(2.0, grade.autoPossible, 0.001)
        assertEquals(8.0, grade.pendingManualMarks, 0.001)
        assertEquals(2, grade.answeredCount)
        assertFalse(grade.isFullyAutoGradable)
    }

    @Test
    fun paperEncodingRoundTrips() {
        val original = ExamPaperCodec.parseBundle(validPaper, requireAnswerKey = true).paper
        val restored = ExamPaperCodec.decodePaper(ExamPaperCodec.encodePaper(original))
        assertEquals(original.title, restored.title)
        assertEquals(original.questions.map { it.id }, restored.questions.map { it.id })
        assertEquals(original.totalMarks, restored.totalMarks, 0.001)
    }

    @Test
    fun answerSerializationRoundTrips() {
        val answers = mapOf("q1" to "C", "q2" to "A whole number")
        assertEquals(answers, ExamPaperCodec.decodeAnswers(ExamPaperCodec.encodeAnswers(answers)))
    }
}