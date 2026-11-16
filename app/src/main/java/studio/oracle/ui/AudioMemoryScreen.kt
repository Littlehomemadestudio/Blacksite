package studio.oracle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.AudioMemoryRepository
import studio.oracle.data.GeminiAudioTranscriber
import studio.oracle.data.ModelPreferences
import studio.oracle.data.WavAudioRecorder
import studio.oracle.data.local.AudioMemoryEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AudioMemoryScreen(workspaceId: String) {
    val context = LocalContext.current
    val palette = LocalOraclePalette.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(context, workspaceId) {
        AudioMemoryRepository(context, database.audioMemoryDao(), workspaceId)
    }
    val keyStore = remember(context) { SecureKeyStore(context) }
    val modelPreferences = remember(context) { ModelPreferences(context) }
    val model by modelPreferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)
    val records by repository.records.collectAsState(initial = emptyList())
    val recorder = remember(context) { WavAudioRecorder(context.applicationContext) }
    val transcriber = remember { GeminiAudioTranscriber() }
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var activeRecordingFile by remember { mutableStateOf<java.io.File?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<AudioMemoryEntity>()) }
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("LOCAL AUDIO ARCHIVE READY") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmTranscriptionFor by remember { mutableStateOf<AudioMemoryEntity?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<AudioMemoryEntity?>(null) }
    var transcribingId by remember { mutableStateOf<String?>(null) }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackRecordId by remember { mutableStateOf<String?>(null) }
    var playbackPlaying by remember { mutableStateOf(false) }
    var playbackLoading by remember { mutableStateOf(false) }
    val recordingNow by rememberUpdatedState(isRecording)
    val playerNow by rememberUpdatedState(player)

    val selectedRecord = records.firstOrNull { it.id == selectedRecordId }

    suspend fun beginRecording() {
        if (isRecording) return
        error = null
        status = "INITIALIZING MICROPHONE"
        try {
            val file = withContext(Dispatchers.IO) {
                repository.newRecordingFile().also(recorder::start)
            }
            activeRecordingFile = file
            elapsedMs = 0L
            recordingStartedAt = SystemClock.elapsedRealtime()
            isRecording = true
            status = "RECORDING LOCALLY · NO NETWORK"
        } catch (failure: Exception) {
            error = failure.message ?: "Could not start microphone capture."
            status = "MICROPHONE UNAVAILABLE"
            withContext(Dispatchers.IO) { recorder.cancel() }
        }
    }

    suspend fun finishRecording() {
        if (!isRecording && activeRecordingFile == null) return
        val file = activeRecordingFile ?: return
        val duration = elapsedMs.coerceAtLeast(1_000L)
        isRecording = false
        status = "FINALIZING WAV RECORDING"
        var completedFile: java.io.File? = null
        try {
            completedFile = withContext(Dispatchers.IO) { recorder.stop() }
            val title = "Recording " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
            val record = repository.saveRecording(title, completedFile, duration)
            selectedRecordId = record.id
            status = "SAVED LOCALLY · " + formatDuration(duration)
        } catch (failure: Exception) {
            withContext(Dispatchers.IO) { recorder.cancel() }
            completedFile?.delete()
            file.delete()
            error = failure.message ?: "Could not save this recording."
            status = "RECORDING SAVE FAILED"
        } finally {
            activeRecordingFile = null
        }
    }

    fun runTranscription(record: AudioMemoryEntity) {
        if (transcriptionJob?.isActive == true) return
        transcriptionJob = scope.launch {
            transcribingId = record.id
            error = null
            status = "UPLOADING APPROVED AUDIO TO GEMINI"
            try {
                val apiKey = withContext(Dispatchers.IO) { keyStore.read().orEmpty() }
                if (apiKey.isBlank()) throw IOException("Connect a Gemini API key in Settings before transcribing.")
                val file = repository.fileFor(record)
                    ?: throw IOException("The local audio file is missing. This record cannot be transcribed.")
                val result = transcriber.analyze(apiKey, model, file)
                if (!repository.saveAnalysis(record.id, result.analysis)) {
                    throw IOException("The recording was removed before its analysis could be saved.")
                }
                status = if (result.remoteFileDeleted) {
                    "TRANSCRIPT SAVED LOCALLY · GEMINI FILE DELETED"
                } else {
                    "ANALYSIS SAVED · REMOTE CLEANUP UNCONFIRMED"
                }
                if (!result.remoteFileDeleted) {
                    error = "Gemini analysis was saved locally, but ORACLE could not confirm deletion of the temporary uploaded file."
                }
            } catch (cancelled: CancellationException) {
                status = "TRANSCRIPTION CANCELLED"
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Audio transcription failed. Retry when ready."
                status = "TRANSCRIPTION FAILED"
            } finally {
                transcribingId = null
                transcriptionJob = null
            }
        }
    }

    fun stopPlayback() {
        runCatching { player?.release() }
        player = null
        playbackRecordId = null
        playbackPlaying = false
        playbackLoading = false
    }

    fun togglePlayback(record: AudioMemoryEntity) {
        if (playbackRecordId == record.id && player != null) {
            if (playbackPlaying) {
                runCatching { player?.pause() }
                playbackPlaying = false
            } else {
                runCatching { player?.start() }
                playbackPlaying = true
            }
            return
        }
        val file = repository.fileFor(record)
        if (file == null) {
            error = "The local recording file is unavailable."
            return
        }
        stopPlayback()
        try {
            val next = MediaPlayer()
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { media ->
                media.start()
                playbackRecordId = record.id
                playbackPlaying = true
                playbackLoading = false
            }
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                error = "Android could not play this recording."
                stopPlayback()
                true
            }
            player = next
            playbackRecordId = record.id
            playbackLoading = true
            next.prepareAsync()
        } catch (failure: Exception) {
            stopPlayback()
            error = failure.message ?: "Could not play this recording."
        }
    }

    LaunchedEffect(searchQuery, records) {
        if (searchQuery.isBlank()) {
            searchResults = records
        } else {
            delay(180)
            searchResults = runCatching { repository.search(searchQuery) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(isRecording, recordingStartedAt) {
        if (isRecording) {
            while (true) {
                delay(250)
                val elapsed = (SystemClock.elapsedRealtime() - recordingStartedAt).coerceAtLeast(0L)
                elapsedMs = elapsed
                if (elapsed >= WavAudioRecorder.MAX_DURATION_MS) {
                    status = "10-MINUTE CAPTURE LIMIT REACHED · SAVING"
                    scope.launch { finishRecording() }
                    break
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { beginRecording() }
        else error = "Microphone permission was denied. ORACLE did not record audio."
    }

    DisposableEffect(recorder) {
        onDispose {
            if (recordingNow) recorder.cancel()
            runCatching { playerNow?.release() }
        }
    }

    if (confirmTranscriptionFor != null) {
        val record = confirmTranscriptionFor!!
        AlertDialog(
            onDismissRequest = { confirmTranscriptionFor = null },
            title = { Text("Send this recording to Gemini?") },
            text = {
                Text(
                    "ORACLE will upload only “" + record.title + "” to Google's Gemini Files API using your selected model. " +
                        "Gemini 3.5 Transcribe creates the verbatim transcript; your selected model then extracts a summary, tasks, and decisions. " +
                        "ORACLE attempts immediate remote deletion. Google automatically deletes Files API uploads after 48 hours if cleanup fails. " +
                        "Your original WAV remains in this workspace. This upload is not automatic."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTranscriptionFor = null
                    runTranscription(record)
                }) { Text("UPLOAD & TRANSCRIBE") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTranscriptionFor = null }) { Text("CANCEL") }
            },
            containerColor = palette.surface,
            titleContentColor = palette.text,
            textContentColor = palette.textSecondary
        )
    }

    if (confirmDeleteFor != null) {
        val record = confirmDeleteFor!!
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text("Delete this recording?") },
            text = { Text("This removes the local WAV and its transcript from this workspace. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFor = null
                    scope.launch {
                        val result = repository.delete(record.id)
                        if (result.recordRemoved) {
                            if (selectedRecordId == record.id) selectedRecordId = null
                            stopPlayback()
                            status = "RECORDING REMOVED"
                            if (!result.fileRemoved) error = "The record was removed, but its local audio file could not be deleted."
                        }
                    }
                }) { Text("DELETE RECORDING") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) { Text("KEEP") }
            },
            containerColor = palette.surface,
            titleContentColor = palette.text,
            textContentColor = palette.textSecondary
        )
    }

    Column(
        Modifier.fillMaxSize().background(palette.canvas).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (selectedRecord != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OracleIconButton(
                    glyph = OracleGlyph.Back,
                    description = "Back to audio archive",
                    onClick = { selectedRecordId = null },
                    tint = palette.accent,
                    size = 36.dp
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("AUDIO MEMORY / RECORD", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.4.sp)
                    Text(selectedRecord.title, color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AudioActionButton("DELETE", onClick = { confirmDeleteFor = selectedRecord })
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AudioPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LOCAL WAV", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.2.sp)
                            Text(
                                formatDuration(selectedRecord.durationMs) + " · " +
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(selectedRecord.createdAt)),
                                color = palette.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                        AudioActionButton(
                            if (playbackLoading && playbackRecordId == selectedRecord.id) "LOADING"
                            else if (playbackRecordId == selectedRecord.id && playbackPlaying) "PAUSE"
                            else "PLAY",
                            onClick = { togglePlayback(selectedRecord) },
                            enabled = !playbackLoading
                        )
                    }
                    AudioDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selectedRecord.transcript.isBlank()) "NOT TRANSCRIBED" else "TRANSCRIPT AVAILABLE",
                            modifier = Modifier.weight(1f),
                            color = palette.textSecondary,
                            fontFamily = OracleMono,
                            fontSize = 8.sp,
                            letterSpacing = 1.sp
                        )
                        if (transcribingId == selectedRecord.id) {
                            AudioActionButton("CANCEL", onClick = { transcriptionJob?.cancel() })
                        } else {
                            AudioActionButton(
                                if (selectedRecord.transcript.isBlank()) "TRANSCRIBE WITH GEMINI" else "RE-ANALYZE",
                                onClick = { confirmTranscriptionFor = selectedRecord },
                                primary = true
                            )
                        }
                    }
                    Text(
                        "ASR: gemini-3.5-transcribe · analysis: " + model,
                        color = palette.textTertiary,
                        fontFamily = OracleMono,
                        fontSize = 8.sp
                    )
                }
                if (error != null) {
                    AudioPanel { Text(error!!, color = palette.error, fontSize = 11.sp, lineHeight = 16.sp) }
                }
                if (selectedRecord.summary.isNotBlank()) {
                    AudioPanel {
                        Text("SUMMARY", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.3.sp)
                        Text(selectedRecord.summary, color = palette.text, fontFamily = OracleSans, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                val tasks = AudioMemoryRepository.decodeList(selectedRecord.tasksJson)
                if (tasks.isNotEmpty()) {
                    AudioPanel {
                        Text("EXTRACTED TASKS", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.3.sp)
                        tasks.forEachIndexed { index, task ->
                            Text((index + 1).toString() + ". " + task, color = palette.text, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
                val decisions = AudioMemoryRepository.decodeList(selectedRecord.decisionsJson)
                if (decisions.isNotEmpty()) {
                    AudioPanel {
                        Text("DECISIONS", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.3.sp)
                        decisions.forEach { decision ->
                            Text("— " + decision, color = palette.text, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
                AudioPanel {
                    Text("FULL TRANSCRIPT", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.3.sp)
                    Text(
                        selectedRecord.transcript.ifBlank { "No transcript yet. Choose Transcribe with Gemini to analyze this recording." },
                        color = if (selectedRecord.transcript.isBlank()) palette.textTertiary else palette.text,
                        fontFamily = OracleSans,
                        fontSize = 12.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AUDIO MEMORY / WORKSPACE ARCHIVE", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.5.sp)
                    Text("Capture. Understand. Recall.", color = palette.text, fontFamily = OracleSans, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(records.size.toString() + " local recordings · searchable offline", color = palette.textSecondary, fontSize = 10.sp)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(palette.surface)
                        .border(1.dp, palette.outlineSoft, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    Text("WAV / 16 kHz", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp)
                }
            }

            AudioCapturePanel(
                isRecording = isRecording,
                elapsedMs = elapsedMs,
                onStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        scope.launch { beginRecording() }
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = { scope.launch { finishRecording() } }
            )

            if (error != null) {
                AudioPanel { Text(error!!, color = palette.error, fontSize = 11.sp, lineHeight = 16.sp) }
            }
            Text(status, color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 0.8.sp)

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(palette.surface).border(1.dp, palette.outlineSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OracleGlyphIcon(OracleGlyph.Search, Modifier.size(15.dp), palette.textTertiary)
                Spacer(Modifier.width(9.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = palette.text, fontFamily = OracleSans, fontSize = 12.sp),
                    cursorBrush = SolidColor(palette.accent),
                    decorationBox = { inner ->
                        if (searchQuery.isBlank()) {
                            Text("Search transcripts, summaries, tasks…", color = palette.textTertiary, fontSize = 11.sp)
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotBlank()) {
                    AudioActionButton("CLEAR", onClick = { searchQuery = "" })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT CAPTURES", color = palette.textSecondary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(searchResults.size.toString() + " MATCHES", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp)
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (searchResults.isEmpty()) {
                    item {
                        AudioPanel {
                            Text(
                                if (searchQuery.isBlank()) "No recordings yet. Start a local capture above; nothing is uploaded until you approve transcription."
                                else "No local audio memories match this search.",
                                color = palette.textSecondary,
                                fontSize = 11.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                items(searchResults, key = { it.id }) { record ->
                    AudioRecordRow(
                        record = record,
                        onClick = {
                            error = null
                            selectedRecordId = record.id
                        },
                        onDelete = { confirmDeleteFor = record }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioCapturePanel(
    isRecording: Boolean,
    elapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val palette = LocalOraclePalette.current
    AudioPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (isRecording) palette.error.copy(alpha = 0.16f) else palette.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(if (isRecording) 11.dp else 9.dp).clip(CircleShape)
                        .background(if (isRecording) palette.error else palette.accent)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isRecording) "MICROPHONE ACTIVE" else "LOCAL CAPTURE READY",
                    color = if (isRecording) palette.error else palette.text,
                    fontFamily = OracleMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.1.sp
                )
                Text(
                    if (isRecording) "Audio is being written to this device only."
                    else "16 kHz mono WAV · maximum 10 minutes per recording",
                    color = palette.textSecondary,
                    fontSize = 10.sp
                )
            }
            Text(formatDuration(elapsedMs), color = palette.text, fontFamily = OracleMono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(palette.outlineSoft)) {
            Box(
                Modifier.fillMaxWidth((elapsedMs.toFloat() / WavAudioRecorder.MAX_DURATION_MS.toFloat()).coerceIn(0f, 1f))
                    .fillMaxHeight().clip(CircleShape)
                    .background(if (isRecording) palette.error else palette.accent)
            )
        }
        AudioActionButton(
            if (isRecording) "STOP & SAVE RECORDING" else "START RECORDING",
            onClick = if (isRecording) onStop else onStart,
            primary = !isRecording,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Microphone permission is requested only when you start. No background capture or automatic upload. Local files are not encrypted at rest.",
            color = palette.textTertiary,
            fontSize = 9.sp,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun AudioRecordRow(
    record: AudioMemoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(palette.surface).border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(palette.accent.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            OracleGlyphIcon(OracleGlyph.Audio, Modifier.size(17.dp), palette.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(record.title, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDuration(record.durationMs) + " · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt)),
                color = palette.textTertiary,
                fontFamily = OracleMono,
                fontSize = 8.sp,
                maxLines = 1
            )
            Text(
                if (record.transcript.isNotBlank()) record.transcript.take(110).replace('\n', ' ') else "Local WAV · not transcribed",
                color = palette.textSecondary,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onDelete) { Text("DELETE", color = palette.textTertiary, fontSize = 8.sp) }
    }
}

@Composable
private fun AudioPanel(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalOraclePalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.surface).border(1.dp, palette.outlineSoft, RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun AudioDivider() {
    val palette = LocalOraclePalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.outlineSoft))
}

@Composable
private fun AudioActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (primary && enabled) palette.accent else palette.canvas)
            .border(1.dp, if (primary && enabled) palette.accent else palette.outlineSoft, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary && enabled) palette.canvas else if (enabled) palette.textSecondary else palette.textTertiary,
            fontFamily = OracleMono,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.55.sp,
            maxLines = 1
        )
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
