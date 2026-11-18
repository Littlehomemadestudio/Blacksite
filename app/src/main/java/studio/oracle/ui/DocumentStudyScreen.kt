package studio.oracle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.oracle.data.DocumentStudyCodec
import studio.oracle.data.DocumentStudyKind
import studio.oracle.data.DocumentStudyPayload
import studio.oracle.data.DocumentStudyPrompt
import studio.oracle.data.DocumentStudyRepository
import studio.oracle.data.DocumentStudySource
import studio.oracle.data.DocumentStudySources
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiReplyResult
import studio.oracle.data.ModelPreferences
import studio.oracle.data.local.DocumentChunkEntity
import studio.oracle.data.local.DocumentEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun DocumentStudyScreen(
    workspaceId: String,
    document: DocumentEntity,
    chunks: List<DocumentChunkEntity>,
    onBack: () -> Unit
) {
    val palette = LocalOraclePalette.current
    val context = LocalContext.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database, workspaceId) {
        DocumentStudyRepository(database.documentDao(), database.documentStudyDao(), workspaceId)
    }
    val studySets by remember(repository, document.id) {
        repository.observe(document.id)
    }.collectAsState(initial = emptyList())
    val keyStore = remember(context) { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val preferences = remember(context) { ModelPreferences(context) }
    val model by preferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)
    val scope = rememberCoroutineScope()

    var selectedKind by remember { mutableStateOf(DocumentStudyKind.SUMMARY) }
    var selectedSetId by remember { mutableStateOf<String?>(null) }
    var pendingSources by remember { mutableStateOf(emptyList<DocumentStudySource>()) }
    var showConfirmation by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("STUDY MATERIALS SAVED ON DEVICE") }
    var error by remember { mutableStateOf<String?>(null) }
    var revealedCards by remember { mutableStateOf(emptySet<Int>()) }
    val selectedAnswers = remember { mutableStateMapOf<Int, Int>() }
    var quizChecked by remember { mutableStateOf(false) }

    val selectedSet = studySets.firstOrNull { it.id == selectedSetId }
    val selectedPayload = remember(selectedSet?.id, selectedSet?.contentJson) {
        selectedSet?.let { row ->
            val kind = DocumentStudyKind.fromWire(row.kind)
            if (kind == null) null else runCatching { DocumentStudyCodec.decode(kind, row.contentJson) }.getOrNull()
        }
    }
    val selectedKindForViewer = selectedSet?.let { DocumentStudyKind.fromWire(it.kind) }
    val selectedManifest = remember(selectedSet?.id, selectedSet?.sourceManifestJson) {
        selectedSet?.let { runCatching { DocumentStudySources.readManifest(it.sourceManifestJson) }.getOrDefault(emptyList()) }.orEmpty()
    }

    BackHandler(enabled = true) {
        when {
            showConfirmation -> showConfirmation = false
            selectedSetId != null -> {
                selectedSetId = null
                revealedCards = emptySet()
                selectedAnswers.clear()
                quizChecked = false
            }
            else -> onBack()
        }
    }

    fun requestGeneration() {
        if (generating) return
        pendingSources = DocumentStudySources.select(chunks)
        if (pendingSources.isEmpty()) {
            error = "This document has no readable passages to study."
            return
        }
        error = null
        showConfirmation = true
    }

    fun sendApprovedSources() {
        if (generating) return
        val approvedSources = pendingSources.toList()
        val approvedKind = selectedKind
        val approvedModel = model
        showConfirmation = false
        error = null
        generationJob = scope.launch {
            generating = true
            status = "GENERATING " + approvedKind.label.uppercase()
            try {
                val key = keyStore.read().orEmpty()
                if (key.isBlank()) {
                    error = "Add a Gemini API key in Settings before generating study materials."
                    status = "GEMINI KEY REQUIRED"
                    return@launch
                }
                val prompt = DocumentStudyPrompt.build(approvedKind, document.title, approvedSources)
                when (val result = api.generate(key, prompt, emptyList(), approvedModel)) {
                    is GeminiReplyResult.Success -> {
                        val payload = DocumentStudyCodec.parse(
                            approvedKind,
                            result.text,
                            approvedSources.map { it.id }.toSet()
                        )
                        val saved = repository.save(document, approvedKind, result.model, payload, approvedSources)
                        selectedSetId = saved.id
                        selectedKind = approvedKind
                        revealedCards = emptySet()
                        selectedAnswers.clear()
                        quizChecked = false
                        status = "SAVED LOCALLY · " + approvedKind.label.uppercase()
                    }
                    is GeminiReplyResult.Failed -> {
                        error = result.message
                        status = "GENERATION FAILED"
                    }
                }
            } catch (cancelled: CancellationException) {
                status = "GENERATION CANCELLED"
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                error = failure.message ?: "Gemini returned an invalid study aid. Try again."
                status = "INVALID STUDY RESPONSE"
            } catch (_: Exception) {
                error = "Could not generate this study aid. Check your connection and retry."
                status = "GENERATION FAILED"
            } finally {
                generating = false
                generationJob = null
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(palette.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OracleIconButton(OracleGlyph.Back, "Back to document", onClick = {
                if (selectedSetId != null) selectedSetId = null else onBack()
            }, tint = palette.accent, size = 36.dp)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("DOCUMENT STUDY", color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.4.sp)
                Text(document.title, color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (generating) {
                OracleTextAction("CANCEL", onClick = { generationJob?.cancel() }, accent = true)
            }
        }

        if (selectedSetId == null) {
            StudyCard {
                Text("TURN THIS DOCUMENT INTO A STUDY SET", color = palette.text, fontFamily = OracleMono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Text(
                    "Choose a format. ORACLE selects a bounded set of passages and shows you exactly what will be sent before contacting Gemini.",
                    color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentStudyKind.entries.forEach { kind ->
                        StudyKindChip(
                            label = kind.label.uppercase(),
                            selected = selectedKind == kind,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedKind = kind }
                        )
                    }
                }
                OracleSolidAction(
                    label = if (generating) "GENERATING…" else "GENERATE " + selectedKind.label.uppercase(),
                    onClick = { requestGeneration() },
                    enabled = !generating && chunks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Only approved excerpts are sent. The original file and full library remain local.",
                    color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, lineHeight = 12.sp
                )
            }

            if (error != null) {
                StudyCard {
                    Text(error.orEmpty(), color = palette.error, fontFamily = OracleSans, fontSize = 11.sp)
                    if (pendingSources.isNotEmpty()) {
                        OracleTextAction("REVIEW EXCERPTS & RETRY", onClick = { showConfirmation = true }, accent = true)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SAVED STUDY SETS", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.weight(1f))
                Text(status, color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (studySets.isEmpty()) {
                StudyCard {
                    Text("Nothing generated yet.", color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Summaries, flashcards and quizzes you create here are saved in this workspace for offline revision.", color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                }
            } else {
                studySets.forEach { row ->
                    val kind = DocumentStudyKind.fromWire(row.kind)
                    StudyCard(modifier = Modifier.clickable {
                        selectedSetId = row.id
                        selectedKind = kind ?: DocumentStudyKind.SUMMARY
                        revealedCards = emptySet()
                        selectedAnswers.clear()
                        quizChecked = false
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (kind?.label ?: row.kind.uppercase()) + " · " + row.model,
                                    color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp
                                )
                            }
                            Text("OPEN  ›", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
                        }
                    }
                }
            }
        } else if (selectedSet != null && selectedPayload != null && selectedKindForViewer != null) {
            StudyCard {
                Text(selectedKindForViewer.label.uppercase(), color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 1.3.sp)
                Text(selectedPayload.title, color = palette.text, fontFamily = OracleSans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Saved offline · " + selectedSet.model, color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp)
            }
            when (selectedKindForViewer) {
                DocumentStudyKind.SUMMARY -> {
                    StudyCard {
                        Text("SUMMARY", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.1.sp)
                        Text(selectedPayload.summary, color = palette.text, fontFamily = OracleSans, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    selectedPayload.keyPoints.forEachIndexed { index, point ->
                        StudyCard {
                            Text("KEY POINT " + (index + 1), color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
                            Text(point.text, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, lineHeight = 18.sp)
                            StudyCitations(point.sourceIds, selectedManifest)
                        }
                    }
                }
                DocumentStudyKind.FLASHCARDS -> {
                    Text("Tap a card to reveal its answer.", color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp)
                    selectedPayload.flashcards.forEachIndexed { index, card ->
                        StudyCard(modifier = Modifier.clickable {
                            revealedCards = if (index in revealedCards) revealedCards - index else revealedCards + index
                        }) {
                            Text(if (index in revealedCards) "ANSWER" else "CARD " + (index + 1), color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.sp)
                            Text(card.front, color = palette.text, fontFamily = OracleSans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
                            if (index in revealedCards) {
                                StudyDivider()
                                Text(card.back, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            StudyCitations(card.sourceIds, selectedManifest)
                        }
                    }
                }
                DocumentStudyKind.QUIZ -> {
                    val score = selectedPayload.questions.indices.count { selectedAnswers[it] == selectedPayload.questions[it].correctIndex }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PRACTICE QUIZ", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        if (quizChecked) Text("SCORE  $score / " + selectedPayload.questions.size, color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                    }
                    selectedPayload.questions.forEachIndexed { index, question ->
                        StudyCard {
                            Text("QUESTION " + (index + 1), color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
                            Text(question.prompt, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
                            question.options.forEachIndexed { optionIndex, option ->
                                val isCorrect = quizChecked && optionIndex == question.correctIndex
                                val isWrongSelection = quizChecked && selectedAnswers[index] == optionIndex && optionIndex != question.correctIndex
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isCorrect -> palette.accent.copy(alpha = 0.14f)
                                                isWrongSelection -> palette.error.copy(alpha = 0.12f)
                                                selectedAnswers[index] == optionIndex -> palette.surfaceRaised
                                                else -> palette.canvas
                                            }
                                        )
                                        .border(1.dp, if (isCorrect) palette.accent.copy(alpha = 0.5f) else palette.outlineSoft, RoundedCornerShape(10.dp))
                                        .clickable(enabled = !quizChecked) { selectedAnswers[index] = optionIndex }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(('A'.code + optionIndex).toChar().toString(), color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                                    Spacer(Modifier.width(9.dp))
                                    Text(option, color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 15.sp)
                                }
                            }
                            if (quizChecked) {
                                StudyDivider()
                                Text(
                                    if (selectedAnswers[index] == question.correctIndex) "Correct" else "Correct answer: " + question.options[question.correctIndex],
                                    color = if (selectedAnswers[index] == question.correctIndex) palette.accent else palette.error,
                                    fontFamily = OracleMono, fontSize = 9.sp
                                )
                                Text(question.explanation, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                            StudyCitations(question.sourceIds, selectedManifest)
                        }
                    }
                    OracleSolidAction(
                        label = if (quizChecked) "TRY AGAIN" else "CHECK ANSWERS",
                        onClick = {
                            if (quizChecked) {
                                selectedAnswers.clear()
                                quizChecked = false
                            } else {
                                quizChecked = true
                            }
                        },
                        enabled = quizChecked || selectedAnswers.size == selectedPayload.questions.size,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            StudyCard {
                Text("SOURCE REFERENCES", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.sp)
                selectedManifest.forEach { (id, label) ->
                    Text("[" + id + "] " + label, color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                }
            }
        } else {
            StudyCard {
                Text("This saved study set could not be read.", color = palette.error, fontFamily = OracleSans, fontSize = 12.sp)
                OracleTextAction("BACK TO STUDY SETS", onClick = { selectedSetId = null }, accent = true)
            }
        }
    }

    if (showConfirmation) {
        Dialog(onDismissRequest = { showConfirmation = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.surfaceRaised)
                    .border(1.dp, palette.outline, RoundedCornerShape(22.dp))
                    .padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("APPROVE EXCERPTS FOR GEMINI?", color = palette.text, fontFamily = OracleMono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The following selected passages and the document title will be sent to Google Gemini using " + model + ". The original file and all other passages remain on this device.",
                    color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp
                )
                Column(
                    Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingSources.forEach { source ->
                        StudyDivider()
                        Text("[" + source.id + "] " + source.label, color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
                        Text(source.content, color = palette.text, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OracleTextAction("KEEP LOCAL", onClick = { showConfirmation = false })
                    OracleSolidAction("SEND SELECTED", onClick = { sendApprovedSources() })
                }
            }
        }
    }
}

@Composable
private fun StudyKindChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalOraclePalette.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.14f) else palette.surfaceRaised)
            .border(1.dp, if (selected) palette.accent.copy(alpha = 0.55f) else palette.outlineSoft, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) palette.accent else palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StudyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalOraclePalette.current
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(palette.surface)
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(15.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun StudyCitations(ids: List<String>, manifest: List<Pair<String, String>>) {
    val palette = LocalOraclePalette.current
    val labels = manifest.toMap()
    Text(
        ids.joinToString("  ") { id -> "[" + id + "] " + (labels[id] ?: "Source") },
        color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, lineHeight = 12.sp
    )
}

@Composable
private fun StudyDivider() {
    val palette = LocalOraclePalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.outlineSoft))
}
