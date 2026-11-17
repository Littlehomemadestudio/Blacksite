package studio.oracle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.ExamAnswerKey
import studio.oracle.data.ExamBundle
import studio.oracle.data.ExamGrade
import studio.oracle.data.ExamPaper
import studio.oracle.data.ExamPaperCodec
import studio.oracle.data.ExamQuestion
import studio.oracle.data.ExamRepository
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiReplyResult
import studio.oracle.data.ModelPreferences
import studio.oracle.data.local.ExamPaperEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun ExamStudioScreen(workspaceId: String) {
    val context = LocalContext.current
    val palette = LocalOraclePalette.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database, workspaceId) { ExamRepository(database.examPaperDao(), workspaceId) }
    val keyStore = remember(context) { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val modelPreferences = remember(context) { ModelPreferences(context) }
    val model by modelPreferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)
    val papers by repository.papers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf("create") }
    var subject by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var examType by remember { mutableStateOf("Mock examination") }
    var difficulty by remember { mutableStateOf("Balanced") }
    var language by remember { mutableStateOf("en") }
    var questionCount by remember { mutableStateOf("20") }
    var totalMarks by remember { mutableStateOf("40") }
    var duration by remember { mutableStateOf("60") }
    var questionTypes by remember { mutableStateOf(setOf("multiple_choice", "short_answer", "calculation", "long_answer")) }
    var answerSpace by remember { mutableStateOf("Standard") }
    var answerKeyEnabled by remember { mutableStateOf(true) }
    var strictSource by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(true) }
    var schoolName by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var teacherName by remember { mutableStateOf("") }
    var semester by remember { mutableStateOf("") }
    var examDate by remember { mutableStateOf("") }
    var paperInstructions by remember { mutableStateOf("Read each question carefully. Show all necessary working and write clearly.") }
    var sourceMaterial by remember { mutableStateOf("") }

    var currentBundle by remember { mutableStateOf<ExamBundle?>(null) }
    var currentEntity by remember { mutableStateOf<ExamPaperEntity?>(null) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var attemptMode by remember { mutableStateOf(false) }
    var answerKeyVisible by remember { mutableStateOf(false) }
    var gradeResult by remember { mutableStateOf<ExamGrade?>(null) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun openPaper(entity: ExamPaperEntity) {
        runCatching {
            currentBundle = ExamBundle(
                ExamPaperCodec.decodePaper(entity.paperJson),
                ExamPaperCodec.decodeAnswerKey(entity.answerKeyJson)
            )
            currentEntity = entity
            answers = ExamPaperCodec.decodeAnswers(entity.answersJson)
            attemptMode = entity.status == "IN_PROGRESS"
            answerKeyVisible = false
            gradeResult = null
            page = "paper"
            error = null
            notice = null
        }.onFailure { error = "This saved examination could not be opened. Its local data may be damaged." }
    }

    fun buildPrompt(): String {
        val selectedTypes = questionTypes.ifEmpty { setOf("multiple_choice", "short_answer", "long_answer") }
        val languageLabel = if (language == "fa") "Persian (fa-IR), natural right-to-left Persian" else "English"
        return listOf(
            "You are ORACLE's professional examination-paper author. Create a complete, academically sound examination document, not a chat quiz.",
            "Return ONLY one valid JSON object. No Markdown fences and no prose.",
            "",
            "AUTHORING SPECIFICATION",
            "Title: " + title.ifBlank { subject + " — " + examType },
            "Subject: " + subject,
            "Grade / level: " + grade,
            "Exam type: " + examType,
            "Topics and syllabus coverage: " + topics,
            "Difficulty: " + difficulty,
            "Question count: " + (questionCount.toIntOrNull() ?: 20),
            "Total marks: " + (totalMarks.toDoubleOrNull() ?: 40.0),
            "Duration in minutes: " + (duration.toIntOrNull() ?: 60),
            "Language: " + languageLabel,
            "Allowed question types: " + selectedTypes.joinToString(", "),
            "Answer space density: " + answerSpace,
            "School: " + schoolName,
            "Class: " + className,
            "Teacher: " + teacherName,
            "Semester / term: " + semester,
            "Exam date: " + examDate,
            "Candidate instructions: " + paperInstructions,
            "Include complete answer key and marking rubric: " + answerKeyEnabled,
            "Strictly use only supplied source material: " + strictSource,
            "",
            "SOURCE MATERIAL",
            sourceMaterial,
            "",
            "REQUIREMENTS",
            "1. Produce a real printable examination paper organized into named sections. It must fit the stated grade and subject, not generic trivia.",
            "2. Match the requested question count exactly. Use only allowed question types. Distribute questions across topics and vary recall, understanding, application, analysis and evaluation.",
            "3. Every question has a unique id (q1, q2...), sequential number, type, precise prompt, positive marks, topic, difficulty, cognitiveLevel and answerLines from 0 to 12.",
            "4. Multiple-choice questions have four plausible options and one unambiguous correct answer. True/false questions are unambiguous.",
            "5. The sum of question marks MUST equal totalMarks exactly.",
            "6. Do not include answers or hints in question prompts.",
            "7. In strict source mode, every question and answer must be supported by supplied material. If insufficient, return {\"error\":\"source_insufficient\",\"message\":\"...\"}; never invent syllabus facts.",
            "8. If answer key is enabled, return exactly one answerKey entry per question: questionId, expectedAnswer, acceptedAnswers array, markingGuide. Give partial-credit rules for constructed responses. Otherwise return an empty answerKey.",
            "9. Use sections[].questions. Each section has title, instructions and questions.",
            "",
            "JSON SCHEMA",
            """{"title":"","schoolName":"","subject":"","grade":"","className":"","teacherName":"","semester":"","examDate":"","durationMinutes":60,"totalMarks":40,"language":"en","instructions":["..."],"sections":[{"title":"Section A","instructions":"...","questions":[{"id":"q1","number":1,"type":"multiple_choice","prompt":"...","marks":2,"options":["A","B","C","D"],"topic":"...","difficulty":"medium","cognitiveLevel":"apply","answerLines":0}]}],"answerKey":[{"questionId":"q1","expectedAnswer":"...","acceptedAnswers":["..."],"markingGuide":"..."}]}"""
        ).joinToString("\n")
    }

    fun generatePaper() {
        error = null
        notice = null
        if (subject.isBlank() || grade.isBlank()) {
            error = "Enter a subject and grade before generating an examination."
            return
        }
        val count = questionCount.toIntOrNull()
        val marks = totalMarks.toDoubleOrNull()
        val minutes = duration.toIntOrNull()
        if (count == null || count !in 1..80 || marks == null || marks <= 0.0 || minutes == null || minutes !in 5..300) {
            error = "Check question count (1–80), total marks and duration (5–300 minutes)."
            return
        }
        if (strictSource && sourceMaterial.isBlank()) {
            error = "Strict source mode needs source material. Add notes or syllabus text first."
            return
        }
        if (questionTypes.isEmpty()) {
            error = "Select at least one question type."
            return
        }
        scope.launch {
            generating = true
            try {
                val key = withContext(Dispatchers.IO) { keyStore.read().orEmpty() }
                if (key.isBlank()) {
                    error = "Connect your Gemini API key in Settings before creating an examination."
                    return@launch
                }
                when (val result = api.generate(key, buildPrompt(), emptyList(), model)) {
                    is GeminiReplyResult.Failed -> error = result.message
                    is GeminiReplyResult.Success -> {
                        val bundle = try {
                            ExamPaperCodec.parseBundle(result.text, requireAnswerKey = answerKeyEnabled).also { bundle ->
                                require(bundle.paper.questionCount == count) {
                                    "Gemini returned " + bundle.paper.questionCount + " questions instead of the requested " + count + "."
                                }
                            }
                        } catch (failure: Exception) {
                            error = failure.message ?: "The generated paper did not pass examination validation."
                            null
                        }
                        if (bundle != null) {
                            val saved = repository.save(bundle)
                            currentBundle = bundle
                            currentEntity = saved
                            answers = emptyMap()
                            attemptMode = false
                            answerKeyVisible = false
                            gradeResult = null
                            page = "paper"
                            notice = "Examination generated and saved locally."
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not generate the examination."
            } finally {
                generating = false
            }
        }
    }

    fun updateAnswer(questionId: String, answer: String) {
        answers = answers + (questionId to answer)
        val entity = currentEntity ?: return
        val snapshot = answers
        scope.launch { repository.saveAttempt(entity.id, snapshot, "IN_PROGRESS") }
    }

    fun beginAttempt() {
        val entity = currentEntity ?: return
        attemptMode = true
        answerKeyVisible = false
        gradeResult = null
        scope.launch { repository.saveAttempt(entity.id, answers, "IN_PROGRESS") }
    }

    fun submitAttempt() {
        val bundle = currentBundle ?: return
        val entity = currentEntity ?: return
        val result = ExamPaperCodec.gradeObjective(bundle, answers)
        gradeResult = result
        attemptMode = false
        scope.launch { repository.saveAttempt(entity.id, answers, "SUBMITTED", result.autoEarned) }
        notice = if (result.isFullyAutoGradable) "Attempt submitted and objectively scored."
            else "Attempt submitted. Written responses still need manual marking."
    }

    Column(Modifier.fillMaxSize().background(palette.canvas)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EXAMINATION WORKBENCH", color = palette.accent, fontFamily = OracleMono,
                    fontSize = 9.sp, letterSpacing = 1.15.sp, fontWeight = FontWeight.SemiBold)
                Text("Exam Studio", color = palette.text, fontFamily = OracleSans,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Author · compose · attempt · review", color = palette.textTertiary,
                    fontFamily = OracleSans, fontSize = 11.sp)
            }
            ExamBadge(papers.size.toString() + " PAPERS")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(11.dp)).background(palette.surfaceRaised).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ExamTab("Create", page == "create", Modifier.weight(1f)) { page = "create"; error = null; notice = null }
            ExamTab("My papers", page == "library", Modifier.weight(1f)) { page = "library"; error = null; notice = null }
        }

        when (page) {
            "create" -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                ExamIntroCard()
                ExamPanel("01 / PAPER IDENTITY", "Define the examination before generating it.") {
                    ExamField("Examination title", title, { title = it }, "e.g. Mathematics — Chapter 3 assessment")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ExamField("Subject", subject, { subject = it }, "Mathematics", Modifier.weight(1f))
                        ExamField("Grade / level", grade, { grade = it }, "Grade 10", Modifier.weight(1f))
                    }
                    ExamField("Topics / chapter coverage", topics, { topics = it }, "List chapters, concepts or learning outcomes…", singleLine = false)
                    Text("ASSESSMENT FORMAT", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                    ExamChipGrid(listOf("Practice paper", "Diagnostic test", "Midterm examination", "Final examination", "Mock examination", "Chapter test"),
                        examType) { examType = it }
                }
                ExamPanel("02 / ASSESSMENT BLUEPRINT", "Control length, marks, challenge and question composition.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ExamField("Questions", questionCount, { questionCount = it.filter(Char::isDigit).take(2) }, "20", Modifier.weight(1f))
                        ExamField("Total marks", totalMarks, { totalMarks = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) }, "40", Modifier.weight(1f))
                        ExamField("Duration · min", duration, { duration = it.filter(Char::isDigit).take(3) }, "60", Modifier.weight(1f))
                    }
                    Text("DIFFICULTY PROFILE", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                    ExamChipGrid(listOf("Accessible", "Balanced", "Challenging", "Advanced"), difficulty) { difficulty = it }
                    Text("QUESTION TYPES", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                    val types = listOf(
                        "multiple_choice" to "Multiple choice", "true_false" to "True / false",
                        "short_answer" to "Short answer", "long_answer" to "Long response",
                        "fill_blank" to "Fill blank", "matching" to "Matching",
                        "calculation" to "Calculation", "proof" to "Proof", "essay" to "Essay"
                    )
                    types.chunked(3).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            group.forEach { (id, label) ->
                                ExamToggleChip(label, id in questionTypes, Modifier.weight(1f)) {
                                    questionTypes = if (id in questionTypes) questionTypes - id else questionTypes + id
                                }
                            }
                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Text("PAPER LANGUAGE", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExamChip("English", language == "en", Modifier.weight(1f)) { language = "en" }
                        ExamChip("فارسی", language == "fa", Modifier.weight(1f)) { language = "fa" }
                    }
                }
                ExamPanel("03 / ADVANCED CONTROLS", "School header, source constraints and marking policy.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Advanced authoring controls", Modifier.weight(1f), color = palette.text,
                            fontFamily = OracleSans, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(if (advancedOpen) "COLLAPSE" else "EXPAND",
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable { advancedOpen = !advancedOpen }
                                .padding(7.dp), color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                    }
                    if (advancedOpen) {
                        ExamField("School / institution", schoolName, { schoolName = it }, "School name")
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            ExamField("Class / group", className, { className = it }, "10-A", Modifier.weight(1f))
                            ExamField("Teacher / examiner", teacherName, { teacherName = it }, "Name", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            ExamField("Semester / term", semester, { semester = it }, "First", Modifier.weight(1f))
                            ExamField("Exam date", examDate, { examDate = it }, "YYYY-MM-DD", Modifier.weight(1f))
                        }
                        ExamField("Candidate instructions", paperInstructions, { paperInstructions = it },
                            "Instructions printed at the top of the paper…", singleLine = false)
                        ExamField("Source material / syllabus text", sourceMaterial, { sourceMaterial = it },
                            "Paste lesson notes, textbook excerpts or learning objectives…", singleLine = false)
                        ExamCheck("Strict source mode — do not invent content beyond supplied material", strictSource) { strictSource = it }
                        ExamCheck("Generate a complete answer key and marking rubric", answerKeyEnabled) { answerKeyEnabled = it }
                        Text("ANSWER-SPACE DENSITY", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                        ExamChipGrid(listOf("Compact", "Standard", "Generous"), answerSpace) { answerSpace = it }
                    }
                }
                ExamPanel("04 / GENERATION", "Gemini creates a structured paper; ORACLE validates question and mark data before saving.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MODEL", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
                            Text(model, color = palette.text, fontFamily = OracleMono, fontSize = 11.sp)
                        }
                        ExamBadge(if (answerKeyEnabled) "WITH MARK SCHEME" else "PAPER ONLY")
                    }
                    if (error != null) ExamNotice(error.orEmpty(), true)
                    if (notice != null) ExamNotice(notice.orEmpty(), false)
                    ExamPrimaryButton(if (generating) "GENERATING EXAMINATION…" else "GENERATE COMPLETE EXAM PAPER",
                        enabled = !generating, onClick = ::generatePaper)
                    Text("Generation is user-initiated. ORACLE will not create exam papers during ordinary study conversations.",
                        color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(18.dp))
            }
            "library" -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (papers.isEmpty()) {
                    ExamPanel("NO SAVED PAPERS", "Generated examinations stay on this device and inside this workspace.") {
                        Text("Create a paper to begin your local assessment library.",
                            color = palette.textSecondary, fontFamily = OracleSans, fontSize = 13.sp)
                        ExamPrimaryButton("CREATE AN EXAM", true) { page = "create" }
                    }
                } else papers.forEach { entity ->
                    ExamSavedPaperCard(entity, onOpen = { openPaper(entity) }, onDelete = {
                        scope.launch { repository.delete(entity.id) }
                    })
                }
            }
            "paper" -> {
                val bundle = currentBundle
                if (bundle == null) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                        ExamNotice("No examination is selected.", true)
                        ExamPrimaryButton("BACK TO CREATOR", true) { page = "create" }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (attemptMode) "CANDIDATE VIEW" else "PAPER PREVIEW",
                                    color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.sp)
                                Text(bundle.paper.title, color = palette.text, fontFamily = OracleSans,
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            }
                            ExamBadge(bundle.paper.questionCount.toString() + " QUESTIONS")
                        }
                        ExamPaperDocument(bundle.paper, if (attemptMode) answers else null,
                            if (attemptMode) ::updateAnswer else null)
                        if (error != null) ExamNotice(error.orEmpty(), true)
                        if (notice != null) ExamNotice(notice.orEmpty(), false)
                        if (gradeResult != null) ExamGradePanel(gradeResult!!)
                        if (answerKeyVisible) ExamAnswerKeyPanel(bundle.answerKey, bundle.paper)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!attemptMode) {
                                ExamPrimaryButton("START EXAM", true, Modifier.weight(1f), ::beginAttempt)
                                ExamSecondaryButton("MY PAPERS", Modifier.weight(1f)) { page = "library" }
                            } else {
                                ExamPrimaryButton("SUBMIT ANSWERS", true, Modifier.weight(1f), ::submitAttempt)
                                ExamSecondaryButton("SAVE PROGRESS", Modifier.weight(1f)) {
                                    val entity = currentEntity
                                    if (entity != null) scope.launch {
                                        repository.saveAttempt(entity.id, answers, "IN_PROGRESS")
                                        notice = "Your answers are saved locally."
                                    }
                                }
                            }
                        }
                        if (!attemptMode && bundle.answerKey.isNotEmpty()) {
                            ExamSecondaryButton(if (answerKeyVisible) "HIDE MARKING SCHEME" else "REVEAL MARKING SCHEME",
                                Modifier.fillMaxWidth()) { answerKeyVisible = !answerKeyVisible }
                        }
                        if (gradeResult != null) ExamSecondaryButton("BACK TO PAPER LIBRARY", Modifier.fillMaxWidth()) {
                            page = "library"
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExamPaperDocument(
    paper: ExamPaper,
    answers: Map<String, String>? = null,
    onAnswerChange: ((String, String) -> Unit)? = null
) {
    val rtl = paper.language.startsWith("fa", true) || paper.language.startsWith("ar", true)
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().widthIn(max = 760.dp).background(Color.White)
            .border(2.dp, Color(0xFF202020), RoundedCornerShape(1.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(paper.schoolName.ifBlank { "ORACLE · EXAMINATION PAPER" },
                Modifier.fillMaxWidth().padding(vertical = 3.dp), color = Color(0xFF222222),
                fontFamily = OracleSans, fontSize = 10.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            Text(paper.title, Modifier.fillMaxWidth().padding(vertical = 5.dp), color = Color(0xFF111111),
                fontFamily = OracleSans, fontSize = 17.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            ExamMetadataGrid(paper)
            Row(Modifier.fillMaxWidth().border(0.8.dp, Color.DarkGray)) {
                ExamMetaCell(if (rtl) "نام و نام خانوادگی" else "Student name", "", Modifier.weight(1f))
                ExamMetaCell(if (rtl) "شماره / شناسه" else "Student ID", "", Modifier.weight(1f))
                ExamMetaCell(if (rtl) "کلاس" else "Class", paper.className, Modifier.weight(1f))
            }
            if (paper.instructions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().border(0.7.dp, Color.DarkGray).padding(7.dp)) {
                    Text(if (rtl) "دستورالعمل" else "INSTRUCTIONS", color = Color(0xFF333333),
                        fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    paper.instructions.forEachIndexed { index, instruction ->
                        Text((index + 1).toString() + ". " + instruction, color = Color(0xFF252525),
                            fontFamily = OracleSans, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
            paper.sections.forEach { section ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFF1F1F1))
                        .border(0.7.dp, Color.DarkGray).padding(horizontal = 7.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, Modifier.weight(1f), color = Color.Black,
                            fontFamily = OracleSans, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(section.questions.sumOf { it.marks }.toPaperMarks() + " marks",
                            color = Color(0xFF333333), fontFamily = OracleMono, fontSize = 8.sp)
                    }
                    if (section.instructions.isNotBlank()) {
                        Text(section.instructions, Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                            color = Color(0xFF333333), fontFamily = OracleSans, fontSize = 9.sp)
                    }
                    section.questions.forEach { question ->
                        ExamQuestionOnPaper(question, answers?.get(question.id).orEmpty(),
                            answers != null && onAnswerChange != null) { onAnswerChange?.invoke(question.id, it) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (rtl) "پایان سوالات" else "END OF PAPER", color = Color(0xFF555555),
                    fontFamily = OracleMono, fontSize = 8.sp)
                Text(paper.totalMarks.toPaperMarks() + " marks total", color = Color(0xFF555555),
                    fontFamily = OracleMono, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun ExamMetadataGrid(paper: ExamPaper) {
    val rtl = paper.language.startsWith("fa", true)
    Column(Modifier.fillMaxWidth().border(0.8.dp, Color.DarkGray)) {
        Row(Modifier.fillMaxWidth()) {
            ExamMetaCell(if (rtl) "نام درس" else "Subject", paper.subject, Modifier.weight(1f))
            ExamMetaCell(if (rtl) "پایه" else "Grade", paper.grade, Modifier.weight(1f))
            ExamMetaCell(if (rtl) "نیمسال" else "Term", paper.semester, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            ExamMetaCell(if (rtl) "مدت امتحان" else "Duration", paper.durationMinutes.toString() + " min", Modifier.weight(1f))
            ExamMetaCell(if (rtl) "تاریخ امتحان" else "Date", paper.examDate, Modifier.weight(1f))
            ExamMetaCell(if (rtl) "مجموع بارم" else "Total marks", paper.totalMarks.toPaperMarks(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            ExamMetaCell(if (rtl) "دبیر" else "Teacher", paper.teacherName, Modifier.weight(1f))
            ExamMetaCell(if (rtl) "کلاس" else "Class", paper.className, Modifier.weight(1f))
            ExamMetaCell(if (rtl) "درس" else "Subject", paper.subject, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExamMetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.border(0.5.dp, Color(0xFF777777)).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color(0xFF444444), fontFamily = OracleSans, fontSize = 8.sp, fontWeight = FontWeight.Medium)
        Text(value.ifBlank { " " }, color = Color(0xFF111111), fontFamily = OracleSans,
            fontSize = 9.sp, fontWeight = FontWeight.SemiBold, minLines = 1)
    }
}

@Composable
private fun ExamQuestionOnPaper(
    question: ExamQuestion,
    answer: String,
    interactive: Boolean,
    onAnswerChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().border(0.65.dp, Color(0xFF777777)).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(question.number.toString() + ".", color = Color.Black,
                fontFamily = OracleSans, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(question.prompt, color = Color(0xFF161616), fontFamily = OracleSans,
                    fontSize = 10.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
                val tags = listOf(question.topic, question.cognitiveLevel, question.difficulty).filter { it.isNotBlank() }
                if (tags.isNotEmpty()) Text(tags.joinToString("  ·  "), color = Color(0xFF777777),
                    fontFamily = OracleMono, fontSize = 7.sp, lineHeight = 10.sp)
            }
            Spacer(Modifier.width(5.dp))
            Text(question.marks.toPaperMarks(), color = Color(0xFF333333),
                fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        if (question.options.isNotEmpty()) {
            question.options.forEachIndexed { index, option ->
                val selected = answer == option
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
                    .then(if (interactive) Modifier.clickable { onAnswerChange(option) } else Modifier)
                    .padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).border(0.8.dp,
                        if (selected) Color(0xFF276CA8) else Color(0xFF666666), RoundedCornerShape(50))
                        .background(if (selected) Color(0xFF276CA8) else Color.Transparent, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(('A'.code + index).toChar().toString() + ") " + option,
                        color = Color(0xFF222222), fontFamily = OracleSans, fontSize = 9.sp)
                }
            }
        } else if (interactive) {
            BasicTextField(value = answer, onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = (question.answerLines.coerceAtLeast(2) * 22).dp)
                    .background(Color.White).padding(horizontal = 3.dp, vertical = 4.dp),
                textStyle = TextStyle(color = Color(0xFF151515), fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 16.sp),
                singleLine = false, maxLines = 12,
                decorationBox = { inner ->
                    Box {
                        if (answer.isBlank()) Text("Write your answer here…", color = Color(0xFFAAAAAA),
                            fontFamily = OracleSans, fontSize = 9.sp)
                        inner()
                    }
                })
            repeat(question.answerLines.coerceIn(1, 8)) {
                Box(Modifier.fillMaxWidth().height(18.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(Color(0xFFDDDDDD)))
                }
            }
        } else {
            repeat(question.answerLines.coerceIn(0, 10)) {
                Box(Modifier.fillMaxWidth().height(19.dp)) {
                    Box(Modifier.fillMaxWidth().height(0.7.dp).align(Alignment.BottomCenter).background(Color(0xFFB8B8B8)))
                }
            }
        }
    }
}

@Composable
fun ExamRichArtifact(bundle: ExamBundle, onSubmitText: ((String) -> Unit)?) {
    val palette = LocalOraclePalette.current
    var started by remember(bundle.paper.id) { mutableStateOf(false) }
    var answers by remember(bundle.paper.id) { mutableStateOf(emptyMap<String, String>()) }
    var submitted by remember(bundle.paper.id) { mutableStateOf(false) }
    var grade by remember(bundle.paper.id) { mutableStateOf<ExamGrade?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EXAMINATION DOCUMENT", color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.sp)
                Text(bundle.paper.title, color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            ExamBadge(bundle.paper.totalMarks.toPaperMarks() + " MARKS")
        }
        ExamPaperDocument(bundle.paper, if (started && !submitted) answers else null,
            if (started && !submitted) { id, value -> answers = answers + (id to value) } else null)
        if (!started) {
            ExamPrimaryButton("BEGIN EXAM", true, onClick = { started = true })
        } else if (!submitted) {
            ExamPrimaryButton("SUBMIT EXAM", true, onClick = {
                val result = ExamPaperCodec.gradeObjective(bundle, answers)
                grade = result
                submitted = true
                val payload = "I completed " + bundle.paper.title + ". Answers: " +
                    answers.entries.joinToString("; ") { it.key + " = " + it.value } +
                    ". Automatic score: " + result.autoEarned.toPaperMarks() + "/" +
                    result.autoPossible.toPaperMarks() + ". Marks awaiting manual grading: " +
                    result.pendingManualMarks.toPaperMarks() + ". Please review my written responses."
                onSubmitText?.invoke(payload)
            })
        }
        if (submitted && grade != null) {
            ExamGradePanel(grade!!)
            Text("Written responses may still need marking against the rubric.",
                color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ExamPanel(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalOraclePalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .background(palette.surface).border(1.dp, palette.outlineSoft, RoundedCornerShape(13.dp))
        .padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
        content()
    }
}

@Composable
private fun ExamIntroCard() {
    val palette = LocalOraclePalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .background(palette.surfaceRaised).border(1.dp, palette.outlineSoft, RoundedCornerShape(13.dp))
        .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("A PAPER, NOT A CHAT QUIZ", color = palette.text, fontFamily = OracleSans,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Build a structured assessment with question taxonomy, mark allocation, candidate fields, answer space and a separate marking scheme.",
                color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Text("01", color = palette.accent, fontFamily = OracleMono, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun ExamField(label: String, value: String, onChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true) {
    val palette = LocalOraclePalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        BasicTextField(value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(palette.surfaceRaised).border(1.dp, palette.outlineSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            textStyle = TextStyle(color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, lineHeight = 17.sp),
            singleLine = singleLine, maxLines = if (singleLine) 1 else 6,
            decorationBox = { inner ->
                Box {
                    if (value.isBlank()) Text(placeholder, color = palette.textTertiary,
                        fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                    inner()
                }
            })
    }
}

@Composable
private fun ExamChipGrid(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    options.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { option -> ExamChip(option, option == selected, Modifier.weight(1f)) { onSelect(option) } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExamChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val palette = LocalOraclePalette.current
    Box(modifier.clip(RoundedCornerShape(8.dp))
        .background(if (selected) palette.accentSoft else palette.surfaceRaised)
        .border(1.dp, if (selected) palette.accent.copy(alpha = 0.55f) else palette.outlineSoft, RoundedCornerShape(8.dp))
        .clickable(onClick = onSelect).padding(horizontal = 9.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) palette.accent else palette.textSecondary, fontFamily = OracleSans,
            fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExamToggleChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalOraclePalette.current
    Row(modifier.clip(RoundedCornerShape(7.dp))
        .background(if (selected) palette.accentSoft else palette.surfaceRaised)
        .border(1.dp, if (selected) palette.accent.copy(alpha = 0.5f) else palette.outlineSoft, RoundedCornerShape(7.dp))
        .clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(if (selected) "✓ " else "+ ", color = if (selected) palette.accent else palette.textTertiary,
            fontFamily = OracleMono, fontSize = 9.sp)
        Text(label, color = if (selected) palette.accent else palette.textSecondary,
            fontFamily = OracleSans, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ExamCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalOraclePalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable { onChange(!checked) }
        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(17.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.accent else palette.surfaceRaised)
            .border(1.dp, if (checked) palette.accent else palette.outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun ExamTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalOraclePalette.current
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) palette.surface else Color.Transparent)
        .clickable(onClick = onClick).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) palette.text else palette.textTertiary, fontFamily = OracleSans,
            fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ExamBadge(label: String) {
    val palette = LocalOraclePalette.current
    Text(label.uppercase(), Modifier.clip(RoundedCornerShape(5.dp)).background(palette.accentSoft)
        .padding(horizontal = 7.dp, vertical = 5.dp), color = palette.accent,
        fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ExamPrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalOraclePalette.current
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (enabled) palette.accent else palette.surfaceRaised)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else palette.textTertiary, fontFamily = OracleMono,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExamSecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalOraclePalette.current
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surfaceRaised)
        .border(1.dp, palette.outlineSoft, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = palette.textSecondary, fontFamily = OracleMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExamNotice(message: String, isError: Boolean) {
    val palette = LocalOraclePalette.current
    Text(message, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (isError) Color(0x14B43B32) else palette.accentSoft).padding(10.dp),
        color = if (isError) palette.error else palette.accent, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun ExamSavedPaperCard(entity: ExamPaperEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalOraclePalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(palette.surface)
        .border(1.dp, palette.outlineSoft, RoundedCornerShape(11.dp)).clickable(onClick = onOpen)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entity.title, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(entity.subject + " · " + entity.grade + " · " + entity.durationMinutes + " min",
                color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
            Text(entity.status.replace('_', ' ') + if (entity.score != null) " · AUTO SCORE " + entity.score.toPaperMarks() else "",
                color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
        }
        Text("OPEN", color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text("×", Modifier.clickable(onClick = onDelete).padding(5.dp), color = palette.error, fontSize = 17.sp)
    }
}

@Composable
private fun ExamGradePanel(grade: ExamGrade) {
    val palette = LocalOraclePalette.current
    ExamPanel("ATTEMPT RECEIPT", "Objective grading is separated from written-response marking.") {
        Text("Auto-scored: " + grade.autoEarned.toPaperMarks() + " / " + grade.autoPossible.toPaperMarks(),
            color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Awaiting manual marking: " + grade.pendingManualMarks.toPaperMarks() + " marks",
            color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp)
        Text(grade.answeredCount.toString() + " of " + grade.totalQuestions + " questions answered",
            color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
    }
}

@Composable
private fun ExamAnswerKeyPanel(answerKey: List<ExamAnswerKey>, paper: ExamPaper) {
    val palette = LocalOraclePalette.current
    ExamPanel("MARKING SCHEME", "Keep this hidden while attempting the paper.") {
        answerKey.forEach { key ->
            val question = paper.questions.firstOrNull { it.id == key.questionId }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Q" + (question?.number ?: key.questionId) + " · " + key.expectedAnswer,
                    color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (key.markingGuide.isNotBlank()) Text(key.markingGuide, color = palette.textSecondary,
                    fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

private fun Double.toPaperMarks(): String =
    if (this % 1.0 == 0.0) toInt().toString() else String.format(java.util.Locale.US, "%.1f", this)
