package studio.oracle.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

data class ExamQuestion(
    val id: String,
    val number: Int,
    val type: String,
    val prompt: String,
    val marks: Double,
    val options: List<String> = emptyList(),
    val topic: String = "",
    val difficulty: String = "medium",
    val cognitiveLevel: String = "understand",
    val answerLines: Int = 3
)

data class ExamSection(
    val title: String,
    val instructions: String = "",
    val questions: List<ExamQuestion>
)

data class ExamPaper(
    val id: String,
    val title: String,
    val schoolName: String,
    val subject: String,
    val grade: String,
    val className: String,
    val teacherName: String,
    val semester: String,
    val examDate: String,
    val durationMinutes: Int,
    val totalMarks: Double,
    val language: String,
    val instructions: List<String>,
    val sections: List<ExamSection>
) {
    val questions: List<ExamQuestion> get() = sections.flatMap { it.questions }
    val questionCount: Int get() = questions.size
}

data class ExamAnswerKey(
    val questionId: String,
    val expectedAnswer: String,
    val acceptedAnswers: List<String> = emptyList(),
    val markingGuide: String = ""
)

data class ExamBundle(
    val paper: ExamPaper,
    val answerKey: List<ExamAnswerKey>
)

data class ExamGrade(
    val autoEarned: Double,
    val autoPossible: Double,
    val pendingManualMarks: Double,
    val answeredCount: Int,
    val totalQuestions: Int
) {
    val isFullyAutoGradable: Boolean get() = pendingManualMarks <= 0.0001
}

object ExamPaperCodec {
    val supportedQuestionTypes = setOf(
        "multiple_choice", "true_false", "short_answer", "long_answer",
        "fill_blank", "matching", "calculation", "proof", "essay"
    )

    fun parseBundle(raw: String, requireAnswerKey: Boolean = false): ExamBundle {
        require(raw.isNotBlank()) { "Gemini returned an empty examination." }
        val root = JSONObject(extractJsonObject(raw))
        if (root.has("error")) {
            throw IllegalArgumentException(root.optString("message").ifBlank { "The requested source material is insufficient to build a reliable paper." })
        }
        val language = root.optString("language", "en").ifBlank { "en" }
        val sectionsJson = root.optJSONArray("sections")
        val sections = mutableListOf<ExamSection>()
        if (sectionsJson != null) {
            for (sectionIndex in 0 until sectionsJson.length()) {
                val section = sectionsJson.optJSONObject(sectionIndex) ?: continue
                val questionsJson = section.optJSONArray("questions") ?: JSONArray()
                val questions = parseQuestions(questionsJson, sections.sumOf { it.questions.size })
                if (questions.isNotEmpty()) {
                    sections.add(
                        ExamSection(
                            title = section.optString("title", "Section " + (sectionIndex + 1)),
                            instructions = section.optString("instructions"),
                            questions = questions
                        )
                    )
                }
            }
        } else {
            val questionsJson = root.optJSONArray("questions") ?: JSONArray()
            val questions = parseQuestions(questionsJson, 0)
            if (questions.isNotEmpty()) sections.add(ExamSection("Questions", questions = questions))
        }

        val questions = sections.flatMap { it.questions }
        require(questions.isNotEmpty()) { "The examination contains no questions." }
        require(questions.size <= 80) { "The examination exceeds the 80-question safety limit." }
        require(questions.map { it.id }.distinct().size == questions.size) {
            "Question identifiers must be unique."
        }
        require(questions.map { it.number }.distinct().size == questions.size) {
            "Question numbers must be unique."
        }
        val calculatedMarks = questions.sumOf { it.marks }
        require(calculatedMarks > 0.0) { "The examination must allocate positive marks." }
        val declaredMarks = root.optDouble("totalMarks", calculatedMarks)
        require(declaredMarks > 0.0 && abs(declaredMarks - calculatedMarks) <= 0.51) {
            "Question marks do not add up to the declared examination total."
        }

        val instructionsJson = root.optJSONArray("instructions") ?: JSONArray()
        val instructions = (0 until instructionsJson.length())
            .map { instructionsJson.optString(it).trim() }
            .filter { it.isNotBlank() }
            .take(12)

        val paper = ExamPaper(
            id = root.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = root.optString("title", "Examination").ifBlank { "Examination" }.take(120),
            schoolName = root.optString("schoolName").take(120),
            subject = root.optString("subject", "General").ifBlank { "General" }.take(100),
            grade = root.optString("grade").take(60),
            className = root.optString("className").take(60),
            teacherName = root.optString("teacherName").take(100),
            semester = root.optString("semester").take(60),
            examDate = root.optString("examDate").take(60),
            durationMinutes = root.optInt("durationMinutes", 60).coerceIn(5, 300),
            totalMarks = calculatedMarks,
            language = language.take(12),
            instructions = instructions,
            sections = sections
        )

        val answerJson = root.optJSONArray("answerKey") ?: JSONArray()
        val answerKey = buildList {
            for (index in 0 until answerJson.length()) {
                val item = answerJson.optJSONObject(index) ?: continue
                val accepted = item.optJSONArray("acceptedAnswers") ?: JSONArray()
                add(
                    ExamAnswerKey(
                        questionId = item.optString("questionId"),
                        expectedAnswer = item.optString("expectedAnswer"),
                        acceptedAnswers = (0 until accepted.length())
                            .map { accepted.optString(it) }
                            .filter { it.isNotBlank() }
                            .take(12),
                        markingGuide = item.optString("markingGuide").take(1600)
                    )
                )
            }
        }
        val questionIds = questions.map { it.id }.toSet()
        require(answerKey.all { it.questionId in questionIds }) {
            "The marking scheme refers to an unknown question."
        }
        if (requireAnswerKey) {
            require(answerKey.map { it.questionId }.distinct().size == questions.size &&
                questions.all { question -> answerKey.any { it.questionId == question.id } }) {
                "Gemini did not return a complete marking scheme for every question."
            }
        }
        return ExamBundle(paper, answerKey)
    }

    fun parsePaper(raw: String): ExamPaper = parseBundle(raw, false).paper

    fun encodePaper(paper: ExamPaper): String = paperToJson(paper).toString()

    fun decodePaper(raw: String): ExamPaper = parsePaper(raw)

    fun encodeAnswerKey(answerKey: List<ExamAnswerKey>): String =
        JSONArray().apply {
            answerKey.forEach { item ->
                put(
                    JSONObject()
                        .put("questionId", item.questionId)
                        .put("expectedAnswer", item.expectedAnswer)
                        .put("acceptedAnswers", JSONArray(item.acceptedAnswers))
                        .put("markingGuide", item.markingGuide)
                )
            }
        }.toString()

    fun decodeAnswerKey(raw: String): List<ExamAnswerKey> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val accepted = item.optJSONArray("acceptedAnswers") ?: JSONArray()
                add(
                    ExamAnswerKey(
                        item.optString("questionId"),
                        item.optString("expectedAnswer"),
                        (0 until accepted.length()).map { accepted.optString(it) },
                        item.optString("markingGuide")
                    )
                )
            }
        }
    }

    fun encodeAnswers(answers: Map<String, String>): String =
        JSONObject().apply { answers.forEach { (id, answer) -> put(id, answer) } }.toString()

    fun decodeAnswers(raw: String): Map<String, String> {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optString(key))
            }
        }
    }

    fun gradeObjective(bundle: ExamBundle, answers: Map<String, String>): ExamGrade {
        val keyById = bundle.answerKey.associateBy { it.questionId }
        var earned = 0.0
        var possible = 0.0
        var manual = 0.0
        var answered = 0
        bundle.paper.questions.forEach { question ->
            val answer = answers[question.id].orEmpty().trim()
            if (answer.isNotBlank()) answered++
            val objective = question.type in setOf("multiple_choice", "true_false", "fill_blank")
            if (!objective) {
                manual += question.marks
                return@forEach
            }
            val key = keyById[question.id]
            if (key == null || key.expectedAnswer.isBlank()) {
                manual += question.marks
                return@forEach
            }
            possible += question.marks
            val selectedIndex = question.options.indexOfFirst { normalizeAnswer(it) == normalizeAnswer(answer) }
            val selectedLetter = if (selectedIndex >= 0) ('A'.code + selectedIndex).toChar().toString() else ""
            val candidates = listOf(answer, selectedLetter).map(::normalizeAnswer).filter { it.isNotBlank() }
            val accepted = (key.acceptedAnswers + key.expectedAnswer).map(::normalizeAnswer)
                .filter { it.isNotBlank() }.toSet()
            if (candidates.any { it in accepted }) earned += question.marks
        }
        return ExamGrade(
            autoEarned = earned,
            autoPossible = possible,
            pendingManualMarks = manual,
            answeredCount = answered,
            totalQuestions = bundle.paper.questionCount
        )
    }

    private fun parseQuestions(array: JSONArray, priorCount: Int): List<ExamQuestion> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type = item.optString("type", "short_answer").lowercase()
                require(type in supportedQuestionTypes) { "Unsupported question type: " + type }
                val optionsJson = item.optJSONArray("options") ?: JSONArray()
                val parsedOptions = (0 until optionsJson.length())
                    .map { optionsJson.optString(it).trim() }
                    .filter { it.isNotBlank() }
                    .take(8)
                val options = if (type == "true_false" && parsedOptions.isEmpty()) {
                    listOf("True", "False")
                } else parsedOptions
                if (type == "multiple_choice") {
                    require(options.size in 2..6) { "Multiple-choice questions need 2–6 options." }
                }
                val defaultLines = when (type) {
                    "short_answer", "fill_blank", "true_false", "multiple_choice" -> 1
                    "calculation", "matching" -> 3
                    "proof", "essay" -> 7
                    else -> 4
                }
                add(
                    ExamQuestion(
                        id = item.optString("id").ifBlank { "q" + (priorCount + index + 1) },
                        number = item.optInt("number", priorCount + index + 1).coerceAtLeast(1),
                        type = type,
                        prompt = item.optString("prompt").trim().take(2400),
                        marks = item.optDouble("marks", 1.0),
                        options = options,
                        topic = item.optString("topic").take(100),
                        difficulty = item.optString("difficulty", "medium").take(30),
                        cognitiveLevel = item.optString("cognitiveLevel", "understand").take(40),
                        answerLines = item.optInt("answerLines", defaultLines).coerceIn(0, 12)
                    )
                )
            }
        }.also { questions ->
            require(questions.all { it.prompt.isNotBlank() }) { "Every question must have a prompt." }
            require(questions.all { it.marks > 0.0 && it.marks <= 100.0 }) {
                "Every question must have a valid positive mark allocation."
            }
        }

    private fun paperToJson(paper: ExamPaper): JSONObject = JSONObject().apply {
        put("id", paper.id)
        put("title", paper.title)
        put("schoolName", paper.schoolName)
        put("subject", paper.subject)
        put("grade", paper.grade)
        put("className", paper.className)
        put("teacherName", paper.teacherName)
        put("semester", paper.semester)
        put("examDate", paper.examDate)
        put("durationMinutes", paper.durationMinutes)
        put("totalMarks", paper.totalMarks)
        put("language", paper.language)
        put("instructions", JSONArray(paper.instructions))
        put(
            "sections",
            JSONArray().apply {
                paper.sections.forEach { section ->
                    put(
                        JSONObject()
                            .put("title", section.title)
                            .put("instructions", section.instructions)
                            .put(
                                "questions",
                                JSONArray().apply {
                                    section.questions.forEach { question ->
                                        put(
                                            JSONObject()
                                                .put("id", question.id)
                                                .put("number", question.number)
                                                .put("type", question.type)
                                                .put("prompt", question.prompt)
                                                .put("marks", question.marks)
                                                .put("options", JSONArray(question.options))
                                                .put("topic", question.topic)
                                                .put("difficulty", question.difficulty)
                                                .put("cognitiveLevel", question.cognitiveLevel)
                                                .put("answerLines", question.answerLines)
                                        )
                                    }
                                }
                            )
                    )
                }
            }
        )
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Gemini did not return a JSON examination object." }
        return raw.substring(start, end + 1)
    }

    private fun normalizeAnswer(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}