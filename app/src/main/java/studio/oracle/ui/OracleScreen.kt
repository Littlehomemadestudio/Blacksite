package studio.oracle.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import studio.oracle.data.AgentAttachmentRef
import studio.oracle.data.AgenticEventCodec
import studio.oracle.data.AgentMediaInput
import studio.oracle.data.CreatedAgentFile
import java.io.File
import java.util.UUID
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.CrossMemoryStore
import studio.oracle.data.AgentConversationPolicy
import studio.oracle.data.AgenticEvent
import studio.oracle.data.AgenticResult
import studio.oracle.data.AgenticSettings
import studio.oracle.data.AgenticPermissionPolicy
import studio.oracle.data.GeminiAgenticRuntime
import studio.oracle.data.GeminiFileUploader
import studio.oracle.data.GeminiImageGenerator
import studio.oracle.data.LocalPythonRuntime
import studio.oracle.data.PhoneWorkspaceTools
import studio.oracle.data.AgentRepository
import studio.oracle.data.AgentTurn
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiModel
import studio.oracle.data.GeminiModelsResult
import studio.oracle.data.ModelPreferences
import studio.oracle.data.WorkspaceCreateResult
import studio.oracle.data.WorkspacePolicy
import studio.oracle.data.local.WorkspaceEntity
import studio.oracle.data.local.AgentMessageEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun OracleScreen(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onOpenAudio: () -> Unit,
    workspaceId: String,
    workspaces: List<WorkspaceEntity>,
    onWorkspaceChange: (String) -> Unit,
    onCreateWorkspace: suspend (String) -> WorkspaceCreateResult
) {
    val context = LocalContext.current
    val palette = LocalOraclePalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyStore = remember { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val crossMemory = remember(context) { CrossMemoryStore(context) }
    val agenticRuntime = remember { GeminiAgenticRuntime() }
    val fileUploader = remember { GeminiFileUploader() }
    val imageGenerator = remember { GeminiImageGenerator() }
    val pythonRuntime = remember(context) { LocalPythonRuntime(context) }
    val repository = remember(context, workspaceId) {
        AgentRepository(OracleDatabase.get(context).agentDao(), workspaceId)
    }
    val modelPreferences = remember(context) { ModelPreferences(context) }
    val scope = rememberCoroutineScope()

    val sessions by repository.sessions.collectAsState(initial = emptyList())
    val model by modelPreferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)

    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(emptyList<AgentMessageEntity>()) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val busyLatest by rememberUpdatedState(busy)
    var voiceReplyEnabled by remember { mutableStateOf(false) }
    var voiceInputStarter by remember { mutableStateOf<(() -> Unit)?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val textToSpeech = remember(context) { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    val speechRecognizer = remember(context) { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val voiceReplyLatest by rememberUpdatedState(voiceReplyEnabled)
    DisposableEffect(textToSpeech) {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "oracle-voice-reply" && voiceReplyLatest && !busyLatest) {
                    scope.launch { voiceInputStarter?.invoke() }
                }
            }
            override fun onError(utteranceId: String?) = Unit
        })
        onDispose { textToSpeech.stop(); textToSpeech.shutdown() }
    }
    DisposableEffect(speechRecognizer) { onDispose { speechRecognizer?.destroy() } }
    var availableModels by remember {
        mutableStateOf(listOf(GeminiModel(ModelPreferences.DEFAULT_MODEL, "Gemini 3.5 Flash-Lite")))
    }
    var syncingModels by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedPrompt by remember { mutableStateOf<String?>(null) }
    var failedPromptPersisted by remember { mutableStateOf(false) }
    var suppressCancelledPromptRestore by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var modelPanelOpen by remember { mutableStateOf(false) }
    var voiceHoldActive by remember { mutableStateOf(false) }
    var modelBrowserOpen by remember { mutableStateOf(false) }
    var agenticSettings by remember { mutableStateOf(AgenticSettings()) }
    var agentEvents by remember { mutableStateOf(emptyList<AgenticEvent>()) }
    var selectedAttachments by remember { mutableStateOf(emptyList<AgentAttachmentRef>()) }
    var approvedToolSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingFileUri by remember { mutableStateOf<Uri?>(null) }
    var editingFileName by remember { mutableStateOf("") }
    var editingFileMime by remember { mutableStateOf("text/plain") }
    var editingFileContent by remember { mutableStateOf("") }
    var loadingFile by remember { mutableStateOf(false) }
    var savingFile by remember { mutableStateOf(false) }
    var fileEditorError by remember { mutableStateOf<String?>(null) }
    var grantedTreeUri by remember {
        mutableStateOf(context.getSharedPreferences("oracle_agent_permissions", Context.MODE_PRIVATE)
            .getString("phone_tree_uri", null)?.let(Uri::parse))
    }
    var pendingApproval by remember { mutableStateOf<String?>(null) }
    var approvalDeferred by remember { mutableStateOf<CompletableDeferred<String>?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Attachment"
                AgentAttachmentRef(uri.toString(), name, mime)
            }.getOrNull()
        }
        selectedAttachments = (selectedAttachments + added).distinctBy { it.uri }.take(8)
    }

    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            runCatching {
                val file = File(context.cacheDir, "oracle-camera-" + UUID.randomUUID().toString() + ".jpg")
                file.outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                }
                val attachment = AgentAttachmentRef(Uri.fromFile(file).toString(), file.name, "image/jpeg")
                selectedAttachments = (selectedAttachments + attachment).distinctBy { it.uri }.take(8)
            }.onFailure { error = "Could not attach the camera photo." }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            grantedTreeUri = uri
            context.getSharedPreferences("oracle_agent_permissions", Context.MODE_PRIVATE)
                .edit().putString("phone_tree_uri", uri.toString()).apply()
        }.onFailure { error = "Android could not persist access to that folder. Choose it again." }
    }
    suspend fun authorizeTool(
        action: String,
        permissionGroup: String,
        authorizationSessionId: String,
        onDecision: (AgenticEvent) -> Unit = {}
    ): Boolean {
        val grantKey = AgenticPermissionPolicy.sessionGrantKey(authorizationSessionId, permissionGroup)
        if (grantKey in approvedToolSessions) return true
        val deferred = CompletableDeferred<String>()
        val scopeLabel = permissionGroup.replace('_', ' ').replaceFirstChar { it.uppercase() }
        withContext(Dispatchers.Main.immediate) {
            pendingApproval = action + "\n\nScope: " + scopeLabel + " · this conversation only." +
                "\nAllow for session authorizes this capability group for the rest of this conversation. It does not grant other capabilities."
            approvalDeferred = deferred
        }
        return try {
            val decision = deferred.await()
            val approved = when (decision) {
                "session" -> {
                    withContext(Dispatchers.Main.immediate) {
                        approvedToolSessions = approvedToolSessions + grantKey
                    }
                    true
                }
                "once" -> true
                else -> false
            }
            onDecision(
                AgenticEvent(
                    kind = if (approved) "permission_granted" else "permission_denied",
                    title = if (approved) "Permission approved" else "Permission denied",
                    detail = scopeLabel + " · " + when (decision) {
                        "session" -> "for this conversation"
                        "once" -> "for this action"
                        else -> "denied"
                    }
                )
            )
            approved
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (approvalDeferred === deferred) {
                    approvalDeferred = null
                    pendingApproval = null
                }
            }
        }
    }

    fun openArtifact(uriText: String) {
        val uri = runCatching {
            if (uriText.startsWith("/")) Uri.fromFile(File(uriText)) else Uri.parse(uriText)
        }.getOrNull() ?: return
        scope.launch {
            loadingFile = true
            fileEditorError = null
            editingFileUri = uri
            try {
                val localFile = if (uri.scheme == "file") File(uri.path.orEmpty()) else null
                editingFileName = localFile?.name ?: runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Generated file"
                editingFileMime = if (localFile != null) {
                    when (localFile.extension.lowercase()) {
                        "md", "markdown" -> "text/markdown"
                        "json" -> "application/json"
                        "csv" -> "text/csv"
                        "html", "htm" -> "text/html"
                        else -> "text/plain"
                    }
                } else context.contentResolver.getType(uri) ?: "text/plain"
                editingFileContent = withContext(Dispatchers.IO) {
                    val bytes = if (localFile != null) localFile.readBytes() else {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not open this file.")
                    }
                    require(bytes.size <= 2 * 1024 * 1024) { "In-app editor supports text files up to 2 MB." }
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (exception: Exception) {
                fileEditorError = exception.message ?: "Could not open this file."
            } finally {
                loadingFile = false
            }
        }
    }

    fun saveEditedArtifact() {
        val uri = editingFileUri ?: return
        scope.launch {
            savingFile = true
            fileEditorError = null
            try {
                withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") {
                        File(uri.path.orEmpty()).writeText(editingFileContent, Charsets.UTF_8)
                    } else {
                        context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(editingFileContent.toByteArray(Charsets.UTF_8))
                        } ?: error("Android could not save this file.")
                    }
                }
                editingFileUri = null
            } catch (exception: Exception) {
                fileEditorError = exception.message ?: "Could not save this file."
            } finally {
                savingFile = false
            }
        }
    }

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }

    LaunchedEffect(workspaceId) {
        suppressCancelledPromptRestore = true
        requestJob?.cancel()
        requestJob = null
        busy = false
        activeSessionId = null
        selectedAttachments = emptyList()
        messages = emptyList()
        agentEvents = emptyList()
        prompt = ""
        error = null
        failedPrompt = null
        failedPromptPersisted = false
        try {
            activeSessionId = repository.createSession(model)
        } finally {
            suppressCancelledPromptRestore = false
        }
    }

    LaunchedEffect(sessions) {
        val active = activeSessionId
        if (active == null && sessions.isNotEmpty()) {
            activeSessionId = sessions.first().id
        } else if (active != null && sessions.none { it.id == active }) {
            activeSessionId = sessions.firstOrNull()?.id
        }
    }

    LaunchedEffect(activeSessionId) {
        val sessionId = activeSessionId
        if (sessionId == null) {
            messages = emptyList()
        } else {
            repository.messages(sessionId).collect { stream ->
                messages = stream
            }
        }
    }

    val menuModels = remember(availableModels, model) {
        (availableModels + GeminiModel(model, model)).distinctBy { it.name }
    }

    fun clearTransientState() {
        error = null
        failedPrompt = null
        failedPromptPersisted = false
    }

    fun createNewThread(closeDrawer: Boolean = false) {
        scope.launch {
            activeSessionId = repository.createSession(model)
            selectedAttachments = emptyList()
            agentEvents = emptyList()
            prompt = ""
            clearTransientState()
            modelPanelOpen = false
            keyboard?.hide()
            if (closeDrawer) drawerOpen = false
        }
    }

    fun selectThread(sessionId: String) {
        activeSessionId = sessionId
        selectedAttachments = emptyList()
        agentEvents = emptyList()
        clearTransientState()
        drawerOpen = false
        keyboard?.hide()
    }

    fun refreshModels() {
        if (syncingModels) return
        scope.launch {
            syncingModels = true
            error = null
            try {
                val key = withContext(Dispatchers.IO) { keyStore.read().orEmpty() }
                if (key.isBlank()) {
                    error = "Add a Gemini API key in Settings before refreshing models."
                    return@launch
                }
                when (val result = api.listModels(key)) {
                    is GeminiModelsResult.Success -> {
                        availableModels = result.models
                        if (result.models.isEmpty()) {
                            error = "This key exposes no models that support generateContent."
                        }
                    }
                    is GeminiModelsResult.Failed -> error = result.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not load models. Check the stored key and network connection."
            } finally {
                syncingModels = false
            }
        }
    }

    fun chooseModel(candidate: GeminiModel) {
        scope.launch {
            modelPreferences.setSelectedModel(candidate.name)
            activeSessionId?.let { repository.updateModel(it, candidate.name) }
            modelPanelOpen = false
        }
    }

    fun submitPrompt() {
        val attachmentSnapshot = selectedAttachments
        val submittedText = prompt.trim()
        if ((submittedText.isBlank() && attachmentSnapshot.isEmpty()) || busy) return
        val submitted = submittedText.ifBlank { "Please inspect the attached media and respond to it." }

        val selectedSession = activeSessionId
        val conversation = messages.map { AgentTurn(it.role, it.content) }
        val submission = AgentConversationPolicy.submissionHistory(
            turns = conversation,
            prompt = submitted,
            failedPrompt = failedPrompt,
            failedPromptPersisted = failedPromptPersisted
        )
        val retry = submission.isRetry
        val history = submission.history
        if (!retry) {
            failedPrompt = null
            failedPromptPersisted = false
        }

        prompt = ""
        selectedAttachments = emptyList()
        agentEvents = emptyList()
        error = null
        keyboard?.hide()
        busy = true

        requestJob = scope.launch {
            var userMessagePersisted = retry
            var userMessageId: Long? = if (retry) messages.lastOrNull()?.id else null
            val priorUserTrace = if (retry) {
                messages.lastOrNull()?.let { AgenticEventCodec.decodeTrace(it.agentTrace) }.orEmpty()
            } else emptyList()
            val runEvents = java.util.Collections.synchronizedList(mutableListOf<AgenticEvent>())
            suspend fun persistRunTrace() {
                val messageId = userMessageId ?: return
                val current = synchronized(runEvents) { runEvents.toList() }
                if (current.isNotEmpty()) {
                    repository.updateMessageTrace(
                        messageId,
                        AgenticEventCodec.encodeTrace(priorUserTrace + current)
                    )
                }
            }
            try {
                val sessionId = selectedSession
                    ?: repository.createSession(model).also { activeSessionId = it }

                val key = try {
                    withContext(Dispatchers.IO) { keyStore.read().orEmpty() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "The saved Gemini key could not be decrypted. Reconnect it in Settings."
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submittedText
                    selectedAttachments = attachmentSnapshot
                    return@launch
                }

                if (key.isBlank()) {
                    error = "Connect a Gemini API key in Settings to start chatting."
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submittedText
                    selectedAttachments = attachmentSnapshot
                    return@launch
                }

                val textAttachmentContext = mutableListOf<String>()
                val mediaInputs = try {
                    var totalBytes = 0L
                    val prepared = mutableListOf<AgentMediaInput>()
                    for (attachment in attachmentSnapshot) {
                        val uri = Uri.parse(attachment.uri)
                        val bytes: ByteArray = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                        } ?: error("Could not read " + attachment.name)
                        totalBytes += bytes.size.toLong()
                        require(bytes.size <= 30 * 1024 * 1024 && totalBytes <= 30L * 1024 * 1024) {
                            "Attachments exceed the 30 MB combined limit. Send fewer or smaller files."
                        }
                        val mime = attachment.mimeType.lowercase()
                        when {
                            mime.startsWith("video/") -> {
                                val uploaded = fileUploader.upload(key, attachment.name, mime, bytes)
                                prepared.add(AgentMediaInput(attachment.name, mime, fileUri = uploaded.uri))
                            }
                            mime.startsWith("image/") || mime.startsWith("audio/") ||
                                mime == "application/pdf" || mime == "text/csv" -> {
                                prepared.add(AgentMediaInput(
                                    name = attachment.name,
                                    mimeType = mime,
                                    base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                ))
                            }
                            mime == "text/plain" || mime == "text/markdown" || mime == "application/json" -> {
                                textAttachmentContext.add(
                                    "ATTACHED TEXT FILE: " + attachment.name + "\n" +
                                        bytes.toString(Charsets.UTF_8).take(200000)
                                )
                            }
                            else -> error("Gemini cannot read this attachment type directly: " + attachment.mimeType)
                        }
                    }
                    prepared
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not prepare the attachments for Gemini."
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submittedText
                    selectedAttachments = attachmentSnapshot
                    return@launch
                }

                if (!retry) {
                    userMessageId = repository.appendMessage(
                        sessionId = sessionId,
                        role = "user",
                        content = submitted,
                        model = model,
                        attachments = AgenticEventCodec.encodeAttachments(attachmentSnapshot)
                    )
                    userMessagePersisted = true
                    failedPromptPersisted = true
                }

                val priorContext = AgentConversationPolicy.context(history).joinToString("\n\n") { (role, text) ->
                    (if (role == "assistant") "ORACLE" else "USER") + ": " + text
                }
                val historyText = if (priorContext.isBlank()) "" else
                    "Conversation history (use as context):\n" + priorContext + "\n\n"
                val attachedText = if (textAttachmentContext.isEmpty()) "" else
                    "\n\n" + textAttachmentContext.joinToString("\n\n")
                val agentPrompt = crossMemory.contextBlock() + historyText +
                    "ORACLE RICH CHAT OUTPUT: When a useful question, poll, or choice is appropriate, render it as an interactive block using a fenced `oracle-choice` code block containing valid JSON with keys question (string), options (array of 2-8 strings), and optional multiple (boolean). Do not use this format for ordinary prose. ORACLE renders this block as selectable controls and submits the selection as a user message. Continue to use Markdown for headings, **bold**, *italic*, tables, lists, quotes, and code. When CrossMemory is enabled, append an <oracle-memory> JSON trailer only when a durable, useful, non-sensitive fact or preference should be remembered: {\"items\":[{\"category\":\"preference|project|decision|fact\",\"content\":\"concise standalone memory\"}]}. Never include transient details, guesses, secrets, or sensitive personal data. Do not mention the trailer in the visible answer.\\n\\n" +
                    "Current user request:\\n" + submitted + attachedText
                val tavilyKey = withContext(Dispatchers.IO) { runCatching { keyStore.readTavilyKey() }.getOrNull() }
                val reportCreatedFile: (CreatedAgentFile) -> Unit = { file ->
                    val event = AgenticEvent(
                        kind = file.kind,
                        title = if (file.kind == "generated_image") "Image generated" else "File ready",
                        detail = file.name + " · " + file.mimeType,
                        payload = file.uri
                    )
                    runEvents.add(event)
                    scope.launch(Dispatchers.Main.immediate) {
                        agentEvents = (agentEvents + event).takeLast(60)
                    }
                }
                val executor = PhoneWorkspaceTools(
                    context = context,
                    grantedTree = grantedTreeUri,
                    tavilyKey = tavilyKey,
                    webSearchEnabled = agenticSettings.localTools && agenticSettings.webSearch,
                    phoneFilesEnabled = agenticSettings.localTools && agenticSettings.phoneFiles,
                    localPythonEnabled = agenticSettings.localTools && agenticSettings.localPython,
                    imageGenerationEnabled = agenticSettings.localTools && agenticSettings.imageGeneration,
                    authorize = { action, group ->
                        authorizeTool(action, group, sessionId) { event ->
                            runEvents.add(event)
                            scope.launch(Dispatchers.Main.immediate) {
                                agentEvents = (agentEvents + event).takeLast(60)
                            }
                        }
                    },
                    python = { code ->
                        val before = pythonRuntime.workspaceDirectory().walkTopDown()
                            .filter { it.isFile }
                            .associate { it.absolutePath to (it.lastModified() to it.length()) }
                        val resultText = pythonRuntime.execute(code)
                        val after = pythonRuntime.workspaceDirectory().walkTopDown()
                            .filter { it.isFile }
                            .toList()
                        after.forEach { file ->
                            val stamp = file.lastModified() to file.length()
                            if (before[file.absolutePath] != stamp) {
                                val mime = when (file.extension.lowercase()) {
                                    "md", "markdown" -> "text/markdown"
                                    "json" -> "application/json"
                                    "html", "htm" -> "text/html"
                                    "csv" -> "text/csv"
                                    else -> "text/plain"
                                }
                                reportCreatedFile(CreatedAgentFile(Uri.fromFile(file).toString(), file.name, mime))
                            }
                        }
                        resultText
                    },
                    generateImage = { prompt, aspectRatio ->
                        imageGenerator.generate(
                            apiKey = key,
                            prompt = prompt,
                            aspectRatio = aspectRatio,
                            imageSize = "1K"
                        )
                    },
                    onFileCreated = reportCreatedFile
                )

                when (val result = agenticRuntime.run(
                    apiKey = key,
                    prompt = agentPrompt,
                    model = model,
                    settings = agenticSettings,
                    executor = executor,
                    media = mediaInputs,
                    onEvent = { sourceEvent ->
                        val event = if (
                            sourceEvent.kind in setOf("generated_image", "generated_audio", "generated_video", "generated_document") &&
                            sourceEvent.payload.isNotBlank() &&
                            !sourceEvent.payload.startsWith("http") &&
                            !sourceEvent.payload.startsWith("content://") &&
                            !sourceEvent.payload.startsWith("file://")
                        ) {
                            runCatching {
                                val directory = File(context.filesDir, "oracle-generated").apply { mkdirs() }
                                val bytes = Base64.decode(sourceEvent.payload, Base64.DEFAULT)
                                require(bytes.size <= 40 * 1024 * 1024) { "Generated media exceeds 40 MB." }
                                val mime = sourceEvent.detail.lowercase()
                                val extension = when {
                                    mime.contains("jpeg") -> "jpg"
                                    mime.contains("audio/wav") -> "wav"
                                    mime.contains("audio/ogg") -> "ogg"
                                    sourceEvent.kind == "generated_audio" -> "mp3"
                                    sourceEvent.kind == "generated_video" -> "mp4"
                                    sourceEvent.kind == "generated_document" && mime.contains("pdf") -> "pdf"
                                    sourceEvent.kind == "generated_document" && mime.contains("json") -> "json"
                                    sourceEvent.kind == "generated_document" && mime.contains("markdown") -> "md"
                                    sourceEvent.kind == "generated_document" && mime.contains("text") -> "txt"
                                    else -> "png"
                                }
                                val file = File(directory, UUID.randomUUID().toString() + "." + extension)
                                file.writeBytes(bytes)
                                sourceEvent.copy(payload = file.absolutePath)
                            }.getOrDefault(sourceEvent.copy(detail = "Media output could not be saved locally."))
                        } else sourceEvent
                        runEvents.add(event)
                        scope.launch(Dispatchers.Main.immediate) {
                            agentEvents = (agentEvents + event).takeLast(60)
                        }
                    }
                )) {
                    is AgenticResult.Success -> {
                        val trace = synchronized(runEvents) { runEvents.toList() }
                        val visibleReply = crossMemory.consumeReply(result.text)
                        repository.appendMessage(
                            sessionId = sessionId,
                            role = "assistant",
                            content = visibleReply,
                            model = result.model,
                            agentTrace = AgenticEventCodec.encodeTrace(trace)
                        )
                        failedPrompt = null
                        failedPromptPersisted = false
                        if (voiceReplyEnabled && ttsReady) {
                            textToSpeech.language = Locale.getDefault()
                            textToSpeech.speak(visibleReply.take(5000), TextToSpeech.QUEUE_FLUSH, null, "oracle-voice-reply")
                        }
                    }
                    is AgenticResult.Failed -> {
                        persistRunTrace()
                        error = result.message
                        failedPrompt = submitted
                        failedPromptPersisted = userMessagePersisted
                        selectedAttachments = attachmentSnapshot
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { persistRunTrace() } }
                if (!suppressCancelledPromptRestore) {
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submittedText
                    selectedAttachments = attachmentSnapshot
                }
                throw cancelled
            } catch (exception: Exception) {
                runCatching { persistRunTrace() }
                error = exception.message ?: "Something went wrong. Check your connection and try again."
                failedPrompt = submitted
                failedPromptPersisted = userMessagePersisted
                selectedAttachments = attachmentSnapshot
            } finally {
                busy = false
                requestJob = null
            }
        }
    }

    fun startVoiceInput() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            error = "Speech recognition is not available on this Android device."
            return
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { error = null }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { }
            override fun onError(errorCode: Int) {
                error = when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the microphone and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice input."
                    else -> "Voice recognition stopped. Please try again."
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) {
                    prompt = spoken
                    scope.launch { kotlinx.coroutines.delay(180); submitPrompt() }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { prompt = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { error = "Could not start microphone input. Try again." }
    }

    SideEffect { if (voiceInputStarter == null) voiceInputStarter = { startVoiceInput() } }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput() else {
            voiceReplyEnabled = false
            error = "Allow microphone access to use voice input."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
        ) {
            CustomTopBar(
                onMenu = { drawerOpen = true },
                onNewThread = { createNewThread() },
                voiceReplyEnabled = voiceReplyEnabled,
                onVoiceToggle = {
                    voiceReplyEnabled = !voiceReplyEnabled
                    if (voiceReplyEnabled) microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    else {
                        speechRecognizer?.stopListening()
                        textToSpeech.stop()
                    }
                },
                showTitle = messages.isNotEmpty(),
                conversationTitle = sessions.firstOrNull { it.id == activeSessionId }?.title.orEmpty()
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (messages.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        contentPadding = PaddingValues(top = 18.dp, bottom = 22.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            OracleMessageRow(
                                message = message,
                                onOpenFile = ::openArtifact,
                                modifier = Modifier.animateItem()
                            )
                        }
                        if (busy && agentEvents.isNotEmpty()) {
                            item { LiveAgentActivity(events = agentEvents) }
                        }
                        if (busy) {
                            item { OracleThinkingIndicator() }
                        }
                    }
                } else {
                    OracleEmptyState(onPrompt = { prompt = it })
                }
            }

            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                exit = fadeOut(tween(110)) + shrinkVertically(tween(160))
            ) {
                OracleErrorNotice(
                    message = error.orEmpty(),
                    canRestore = failedPrompt != null && prompt != failedPrompt,
                    onRestore = {
                        prompt = failedPrompt.orEmpty()
                        error = null
                    },
                    onDismiss = { error = null }
                )
            }

            AnimatedVisibility(
                visible = modelPanelOpen,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ModelControlPanel(
                        models = menuModels,
                        selectedModel = model,
                        refreshing = syncingModels,
                        onSelect = ::chooseModel,
                        onRefresh = ::refreshModels,
                        settings = agenticSettings,
                        onSettingsChange = { agenticSettings = it },
                        folderGranted = grantedTreeUri != null,
                        onChooseFolder = { folderPicker.launch(null) },
                        onOpenModelBrowser = { modelBrowserOpen = true },
                        modifier = Modifier.widthIn(max = 350.dp).padding(bottom = 8.dp)
                    )
                }
            }

            DeepSeekInputPill(
                value = prompt,
                onValueChange = { prompt = it },
                busy = busy,
                onSend = { submitPrompt() },
                onCancel = { requestJob?.cancel() },
                onThinkClick = { modelPanelOpen = !modelPanelOpen; keyboard?.hide() },
                thinkExpanded = modelPanelOpen,
                attachments = selectedAttachments,
                onAttachClick = { },
                onRemoveAttachment = { attachment -> selectedAttachments = selectedAttachments.filterNot { it.uri == attachment.uri } },
                onVoiceClick = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                onVoiceRelease = { speechRecognizer?.stopListening() },
                onVoiceCancel = { speechRecognizer?.cancel() },
                onVoiceHoldChanged = { voiceHoldActive = it },
                onCameraClick = { cameraPicker.launch(null) },
                onPhotoClick = { mediaPicker.launch(arrayOf("image/*")) },
                onDocumentClick = { mediaPicker.launch(arrayOf("*/*")) },
                voiceReplyEnabled = voiceReplyEnabled,
                searchEnabled = agenticSettings.localTools && agenticSettings.webSearch,
                onSearchClick = {
                    agenticSettings = agenticSettings.copy(
                        localTools = true,
                        webSearch = !agenticSettings.webSearch
                    )
                },
                onVoiceReplyToggle = {
                    val enabling = !voiceReplyEnabled
                    voiceReplyEnabled = enabling
                    if (enabling) microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    else {
                        speechRecognizer?.stopListening()
                        textToSpeech.stop()
                    }
                },
                modifier = Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
            )

            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(4.dp)))
        }

        if (voiceHoldActive) {
            Box(
                Modifier.fillMaxWidth()
                    .height(270.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.38f to Color.Transparent,
                                0.62f to Color(0xBFD5E5FF),
                                1.0f to Color(0xFF78AFFF)
                            )
                        )
                    )
            ) {
                Text(
                    "Release to send, slide up to cancel",
                    Modifier.align(Alignment.Center).padding(top = 24.dp),
                    color = Color.White, fontFamily = OracleSans, fontSize = 13.sp
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 63.dp).height(28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(7, 12, 18, 10, 22, 15, 26, 12, 19, 9, 25, 14, 20, 8, 16, 24, 11, 18, 7)
                        .forEachIndexed { index, height ->
                            Box(
                                Modifier.padding(horizontal = 2.dp).width(2.dp)
                                    .height((height * if (index % 3 == 0) 1.12f else 1f).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.88f))
                            )
                        }
                }
            }
        }

        if (drawerOpen) {
            OracleThreadDrawer(
                sessions = sessions,
                activeSessionId = activeSessionId,
                workspaces = workspaces,
                activeWorkspaceId = workspaceId,
                onWorkspaceChange = onWorkspaceChange,
                onCreateWorkspace = onCreateWorkspace,
                onClose = { drawerOpen = false },
                onNewThread = { createNewThread(closeDrawer = true) },
                onSelectThread = ::selectThread,
                onOpenLibrary = {
                    drawerOpen = false
                    onOpenLibrary()
                },
                onOpenSettings = {
                    drawerOpen = false
                    onOpenSettings()
                },
                onOpenWorkflows = {
                    drawerOpen = false
                    onOpenWorkflows()
                },
                onOpenAudio = {
                    drawerOpen = false
                    onOpenAudio()
                }
            )
        }
        if (pendingApproval != null) {
            AlertDialog(
                onDismissRequest = { approvalDeferred?.complete("deny"); approvalDeferred = null; pendingApproval = null },
                title = { Text("ORACLE requests access") },
                text = { Text(pendingApproval.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = {
                        approvalDeferred?.complete("session")
                        approvalDeferred = null
                        pendingApproval = null
                    }) { Text("ALLOW FOR SESSION") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            approvalDeferred?.complete("once")
                            approvalDeferred = null
                            pendingApproval = null
                        }) { Text("ONCE") }
                        TextButton(onClick = {
                            approvalDeferred?.complete("deny")
                            approvalDeferred = null
                            pendingApproval = null
                        }) { Text("DENY") }
                    }
                }
            )
        }
        if (modelBrowserOpen) {
            ModelBrowserDialog(
                models = menuModels,
                selectedModel = model,
                refreshing = syncingModels,
                onRefresh = ::refreshModels,
                onSelect = ::chooseModel,
                onDismiss = { modelBrowserOpen = false }
            )
        }
        if (editingFileUri != null) {
            AgentFileEditorDialog(
                fileName = editingFileName,
                mimeType = editingFileMime,
                content = editingFileContent,
                loading = loadingFile,
                saving = savingFile,
                error = fileEditorError,
                onContentChange = { editingFileContent = it },
                onSave = ::saveEditedArtifact,
                onDismiss = { if (!savingFile) editingFileUri = null }
            )
        }
    }
}

@Composable
private fun CustomTopBar(
    onMenu: () -> Unit,
    onNewThread: () -> Unit,
    voiceReplyEnabled: Boolean,
    onVoiceToggle: () -> Unit,
    showTitle: Boolean,
    conversationTitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleIconButton(OracleGlyph.Menu, "Open conversations", onMenu,
            tint = Color(0xFF171717), size = 40.dp)
        if (showTitle) {
            Spacer(Modifier.width(10.dp))
            Text(conversationTitle.ifBlank { "New chat" }, color = Color(0xFF171717),
                fontFamily = OracleSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onVoiceToggle),
            contentAlignment = Alignment.Center) {
            OracleGlyphIcon(
                if (voiceReplyEnabled) OracleGlyph.Speaker else OracleGlyph.SpeakerOff,
                Modifier.size(26.dp), Color(0xFF171717), 1.7f
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNewThread),
            contentAlignment = Alignment.Center) {
            OracleGlyphIcon(OracleGlyph.AddCircle, Modifier.size(28.dp), Color(0xFF171717), 1.7f)
        }
    }
}

@Composable
private fun OracleMessageRow(
    message: AgentMessageEntity,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val attachments = remember(message.attachments) { AgenticEventCodec.decodeAttachments(message.attachments) }
    val events = remember(message.agentTrace) { AgenticEventCodec.decodeTrace(message.agentTrace) }
    androidx.compose.animation.AnimatedVisibility(
        visible = true, modifier = modifier,
        enter = fadeIn(tween(190)) + slideInVertically(tween(230)) { it / 22 },
        exit = fadeOut(tween(120))
    ) {
        if (message.role == "user") {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Column(
                        Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFFEDF2FC)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (message.content.isNotBlank()) Text(message.content,
                            color = Color(0xFF171717), fontFamily = OracleSans,
                            fontSize = 16.sp, lineHeight = 24.sp)
                        attachments.forEach { attachment ->
                        if (attachment.mimeType.startsWith("image/")) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                                .background(Color.White).padding(7.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                AsyncImage(
                                    model = Uri.parse(attachment.uri),
                                    contentDescription = attachment.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                        .clip(RoundedCornerShape(9.dp)))
                                Text(attachment.name, color = Color(0xFF6A6A6A),
                                    fontFamily = OracleSans, fontSize = 11.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                                .background(Color.White).padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(when {
                                    attachment.mimeType.startsWith("video/") -> "▶"
                                    attachment.mimeType.startsWith("audio/") -> "♫"
                                    else -> "▤"
                                }, color = Color(0xFF587DD8), fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(attachment.name, color = Color(0xFF222222),
                                        fontSize = 12.sp, fontFamily = OracleSans,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(attachment.mimeType, color = Color(0xFF8B8B8B),
                                        fontSize = 10.sp, fontFamily = OracleSans)
                                }
                            }
                        }
                    }
                }
                if (events.isNotEmpty()) {
                    Column(Modifier.widthIn(max = 340.dp)) {
                        AgentExecutionTrace(events, onOpenFile)
                    }
                }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (events.isNotEmpty()) AgentExecutionTrace(events, onOpenFile)
                events.filter { it.kind == "file_created" }.forEach { event ->
                    GeneratedFileArtifact(event, onOpenFile)
                }
                events.filter {
                    it.kind == "generated_image" || it.kind == "generated_audio" ||
                        it.kind == "generated_video" || it.kind == "generated_document"
                }.forEach { event -> GeneratedMediaArtifact(event, onOpenFile) }
                OracleRichMessage(message.content, onSubmitText = { answer -> prompt = answer; submitPrompt() })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    events.lastOrNull { it.kind == "usage" }?.let { TokenUsageChip(it.detail) }
                    Text("Copy", Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ORACLE response", message.content))
                    }.padding(horizontal = 8.dp, vertical = 5.dp),
                        color = Color(0xFF909090), fontFamily = OracleSans, fontSize = 11.sp)
                    Text("Share", Modifier.clickable {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message.content)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share response"))
                    }.padding(horizontal = 7.dp, vertical = 5.dp),
                        color = Color(0xFF909090), fontFamily = OracleSans, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GeneratedFileArtifact(event: AgenticEvent, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    val fileName = event.detail.substringBefore(" · ").ifBlank {
        event.payload.substringAfterLast('/').substringAfterLast('\\')
    }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val glyph = when (extension) {
        "png", "jpg", "jpeg", "webp", "svg" -> "▧"
        "pdf" -> "PDF"
        "py" -> "PY"
        "kt", "kts", "java", "js", "ts" -> "</>"
        "md", "markdown" -> "MD"
        "json" -> "{}"
        "csv", "xlsx" -> "▦"
        "mp3", "wav", "ogg", "m4a" -> "♫"
        "mp4", "mov", "webm" -> "▶"
        else -> "TXT"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(palette.surfaceRaised)
            .border(1.dp, Color(0x55E08A45), RoundedCornerShape(13.dp))
            .clickable { onOpenFile(event.payload) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1FE08A45)),
            contentAlignment = Alignment.Center) {
            Text(glyph, color = Color(0xFFE08A45), fontFamily = OracleMono,
                fontWeight = FontWeight.Bold, fontSize = if (glyph.length > 2) 9.sp else 15.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(fileName, color = palette.text, fontFamily = OracleSans,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(event.detail.substringAfter(" · ", "Created in ORACLE workspace"),
                color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("OPEN  ↗", color = Color(0xFFE08A45), fontFamily = OracleMono,
            fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GeneratedMediaArtifact(event: AgenticEvent, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    when (event.kind) {
        "generated_image" -> {
            val bitmap = remember(event.payload) { BitmapFactory.decodeFile(event.payload) }
            bitmap?.let {
                Image(
                    it.asImageBitmap(), contentDescription = "Generated by ORACLE",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(15.dp))
                )
            }
        }
        "generated_document" -> {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surfaceRaised)
                    .clickable { onOpenFile(event.payload) }.padding(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("▤", color = Color(0xFF42A68A), fontSize = 20.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title, color = palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("OPEN GENERATED FILE", color = Color(0xFF42A68A), fontFamily = OracleMono, fontSize = 8.sp)
                }
                Text("↗", color = Color(0xFF42A68A), fontSize = 16.sp)
            }
        }
        "generated_audio" -> AudioArtifactCard(event)
        "generated_video" -> VideoArtifactCard(event)
    }
}

@Composable
private fun AudioArtifactCard(event: AgenticEvent) {
    val palette = LocalOraclePalette.current
    var playing by remember(event.payload) { mutableStateOf(false) }
    var prepared by remember(event.payload) { mutableStateOf(false) }
    val player = remember(event.payload) {
        android.media.MediaPlayer().apply {
            setDataSource(event.payload)
            setOnCompletionListener { playing = false }
            setOnPreparedListener { prepared = true }
            prepareAsync()
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x147B8FE8))
            .border(1.dp, Color(0x337B8FE8), RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x287B8FE8)),
            contentAlignment = Alignment.Center) {
            Text(if (playing) "Ⅱ" else "▶", color = Color(0xFF7B8FE8), fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("GENERATED AUDIO", color = Color(0xFF7B8FE8), fontFamily = OracleMono, fontSize = 8.sp)
            Text(if (prepared) event.detail else "Preparing audio…", color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp)
        }
        androidx.compose.material3.TextButton(
            enabled = prepared,
            onClick = {
                runCatching {
                    if (player.isPlaying) {
                        player.pause()
                        playing = false
                    } else if (prepared) {
                        player.start()
                        playing = true
                    }
                }
            }
        ) { Text(if (!prepared) "LOADING" else if (playing) "PAUSE" else "PLAY") }
    }
}

@Composable
private fun VideoArtifactCard(event: AgenticEvent) {
    val palette = LocalOraclePalette.current
    val uri = remember(event.payload) {
        if (event.payload.startsWith("http") || event.payload.startsWith("content://") || event.payload.startsWith("file://")) {
            Uri.parse(event.payload)
        } else Uri.fromFile(File(event.payload))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(palette.surfaceRaised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("GENERATED VIDEO", color = Color(0xFF7B8FE8), fontFamily = OracleMono, fontSize = 8.sp)
        Text(event.detail, color = palette.textSecondary, fontSize = 10.sp)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                android.widget.VideoView(viewContext).apply {
                    setVideoURI(uri)
                    setMediaController(android.widget.MediaController(viewContext).also { it.setAnchorView(this) })
                }
            },
            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp))
        )
    }
}

@Composable
private fun TokenUsageChip(detail: String) {
    val palette = LocalOraclePalette.current
    var showDetails by remember { mutableStateOf(false) }
    val values = remember(detail) {
        listOf("input", "output", "thinking", "cached", "tools").mapNotNull { key ->
            Regex(key + "\\s+(\\d+)").find(detail)?.groupValues?.getOrNull(1)?.let { key to it }
        }
    }
    val input = values.firstOrNull { it.first == "input" }?.second?.toIntOrNull() ?: 0
    val output = values.firstOrNull { it.first == "output" }?.second?.toIntOrNull() ?: 0
    val total = input + output
    val label = if (total >= 1000) "%.1fk".format(total / 1000f) else total.toString()
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(Color(0x187B8FE8)).border(1.dp, Color(0x557B8FE8), CircleShape)
            .clickable { showDetails = true },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF7488E2), fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Context usage") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    values.forEach { (key, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(key.uppercase(), color = palette.textSecondary, fontFamily = OracleMono, fontSize = 9.sp)
                            Spacer(Modifier.weight(1f))
                            Text("%,d".format(value.toIntOrNull() ?: 0), color = palette.text,
                                fontFamily = OracleMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Usage values are shown when Gemini returns them. Cached tokens are reported separately.",
                        color = palette.textTertiary, fontSize = 10.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("CLOSE") } }
        )
    }
}

@Composable
private fun AgentExecutionTrace(events: List<AgenticEvent>, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    var expanded by remember(events.size, events.lastOrNull()?.title) { mutableStateOf(false) }
    val visibleEvents = events.filter { it.kind != "usage" }
    val actionCount = visibleEvents.count { it.kind.endsWith("start") || it.kind == "file_created" }
    Column(Modifier.fillMaxWidth()) {
        val planSummary = visibleEvents.lastOrNull { it.kind == "plan" }?.detail
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(2.dp).height(20.dp).background(Color(0xFFE08A45), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("REASONING SUMMARY  ·  ACTIVITY", color = Color(0xFFE08A45), fontFamily = OracleMono,
                    fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(
                    planSummary?.takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.take(180)
                        ?: (actionCount.toString() + " actions · " + visibleEvents.size + " events"),
                    color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp,
                    maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "−" else "⌄", color = Color(0xFF7E8FE0), fontSize = 15.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(170)) + expandVertically(tween(260)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(180))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 5.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                visibleEvents.forEachIndexed { index, event -> AgentEventRow(index + 1, event, onOpenFile) }
            }
        }
    }
}

@Composable
private fun AgentEventRow(index: Int, event: AgenticEvent, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    val isFile = event.kind == "file_created"
    val isCode = event.code.isNotBlank()
    var expanded by remember(event.title, event.kind, event.code) { mutableStateOf(false) }
    val accent = when {
        isFile -> Color(0xFF42A68A)
        event.kind.contains("network") -> Color(0xFFE08A45)
        event.kind.contains("python") || event.kind.contains("hosted") -> Color(0xFFBF8B52)
        event.kind.contains("search") || event.kind.contains("url") -> Color(0xFF5C9ACB)
        else -> Color(0xFF7B8FE8)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(25.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                    .background(accent.copy(alpha = 0.13f))
                    .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(index.toString().padStart(2, '0'), color = accent,
                    fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.width(2.dp).heightIn(min = 18.dp).background(accent.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .background(palette.surfaceRaised.copy(alpha = 0.72f))
                .clickable(enabled = isCode || isFile || event.detail.isNotBlank()) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, color = accent, fontFamily = OracleMono, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.25.sp, modifier = Modifier.weight(1f))
                if (isCode || event.detail.isNotBlank()) Text(if (expanded) "−" else "+",
                    color = palette.textTertiary, fontSize = 12.sp)
            }
            if (isFile) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1242A68A)).clickable { onOpenFile(event.payload) }
                        .padding(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▤", color = Color(0xFF42A68A), fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.detail, color = palette.text, fontSize = 11.sp, maxLines = 2)
                        Text("OPEN IN ORACLE EDITOR", color = Color(0xFF42A68A), fontFamily = OracleMono, fontSize = 8.sp)
                    }
                    Text("↗", color = Color(0xFF42A68A), fontSize = 16.sp)
                }
            } else if (event.detail.isNotBlank() && event.kind != "generated_image") {
                Text(event.detail.take(if (expanded) 16000 else 180), color = palette.textSecondary,
                    fontFamily = if (event.kind.contains("python")) OracleMono else OracleSans,
                    fontSize = 10.sp, lineHeight = 15.sp)
            }
            if (isCode && expanded) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF171D29)).padding(9.dp)) {
                    OracleSyntaxCode(event.code.take(16000))
                }
            }
        }
    }
}

@Composable
private fun LiveAgentActivity(events: List<AgenticEvent>) {
    val last = events.lastOrNull { it.kind != "usage" }
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 12.dp)
            .clip(RoundedCornerShape(11.dp)).background(Color(0x147B8FE8))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleSpinner(Modifier.size(15.dp), Color(0xFF788CE5))
        Spacer(Modifier.width(9.dp))
        Text(last?.title ?: "ORACLE is planning the next step",
            Modifier.weight(1f), color = Color(0xFF788CE5), fontFamily = OracleMono,
            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        last?.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(34), color = LocalOraclePalette.current.textTertiary, fontFamily = OracleSans, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun OracleThinkingIndicator() {
    val palette = LocalOraclePalette.current
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "oracle-thinking")
    Row(Modifier.padding(start = 33.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val scale by pulse.animateFloat(
                initialValue = 0.42f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(520, delayMillis = index * 135),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "thinking-dot-$index"
            )
            Box(
                Modifier.padding(end = 5.dp).size(5.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.45f + (scale * 0.55f) }
                    .clip(CircleShape).background(palette.accent)
            )
        }
    }
}

@Composable
private fun AgenticActivityTimeline(events: List<AgenticEvent>) {
    val palette = LocalOraclePalette.current
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        events.forEach { event ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 5 }
            ) {
                Column(Modifier.fillMaxWidth().animateContentSize()
                    .clip(RoundedCornerShape(12.dp)).background(palette.surface)
                    .border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(event.title.uppercase(), color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.55.sp)
                    if (event.detail.isNotBlank()) Text(event.detail, color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp)
                    if (event.code.isNotBlank()) Text(event.code.take(6000), color = palette.text, fontFamily = OracleMono, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun OracleErrorNotice(
    message: String,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.error.copy(alpha = 0.09f))
            .border(1.dp, palette.error.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            message,
            color = palette.error,
            fontFamily = OracleSans,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        if (canRestore) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OracleTextAction("RESTORE", onRestore, accent = true)
                OracleTextAction("DISMISS", onDismiss)
            }
        }
    }
}

@Composable
private fun ModelControlPanel(
    models: List<GeminiModel>,
    selectedModel: String,
    refreshing: Boolean,
    onSelect: (GeminiModel) -> Unit,
    onRefresh: () -> Unit,
    settings: AgenticSettings,
    onSettingsChange: (AgenticSettings) -> Unit,
    folderGranted: Boolean,
    onChooseFolder: () -> Unit,
    onOpenModelBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    val activeModel = models.firstOrNull { it.name == selectedModel }
    val thinkingLevels = when {
        selectedModel.contains("3.8-flash") -> listOf("low", "medium", "high")
        selectedModel.contains("4-hydrogen") -> listOf("low", "medium", "high", "xhigh")
        selectedModel.contains("flash-lite") -> listOf("minimal", "low", "medium")
        else -> listOf("low", "medium", "high")
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    if (palette.isDark) {
                        listOf(Color(0xED303A49), Color(0xD91E2631), Color(0xF0262E3A))
                    } else {
                        listOf(Color(0xF5FFFFFF), Color(0xDCECF1FA), Color(0xF4FFFFFF))
                    }
                )
            )
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MODEL WORKSPACE", color = palette.textTertiary, fontFamily = OracleMono,
                    fontSize = 8.sp, letterSpacing = 1.0.sp)
                Text(activeModel?.displayName ?: selectedModel, color = palette.text,
                    fontFamily = OracleSans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            OracleTextAction(if (refreshing) "SYNCING…" else "SYNC MODELS", onRefresh,
                enabled = !refreshing, accent = true)
        }
        activeModel?.let { model ->
            Text(
                (model.inputTokenLimit?.let { "%,d".format(it) + " input" } ?: "Input limit n/a") +
                    "    ·    " + (model.outputTokenLimit?.let { "%,d".format(it) + " output" } ?: "Output n/a"),
                color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp
            )
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Color(0x157B8FE8))
                .clickable(onClick = onOpenModelBrowser)
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x287B8FE8)),
                    contentAlignment = Alignment.Center) {
                    Text("✦", color = Color(0xFF7185DE), fontSize = 17.sp)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("EXPLORE MODELS", color = Color(0xFF6579CF), fontFamily = OracleMono,
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                    Text("Compare capabilities, context windows and model families",
                        color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp)
                }
                Text("↗", color = Color(0xFF6579CF), fontSize = 16.sp)
            }
        }
        if (selectedModel.contains("flash-image", ignoreCase = true)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(Color(0x1642A68A)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✦", color = Color(0xFF369A7B), fontSize = 17.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("IMAGE GENERATION MODEL", color = Color(0xFF369A7B),
                        fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    Text("Dedicated image output. For web, files and Python, use the main agent model and its DRAW tool.",
                        color = palette.textSecondary, fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        } else {
            Text("QUICK CONTROLS", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 0.9.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelChoice("WEB SEARCH", settings.localTools && settings.webSearch, { onSettingsChange(settings.copy(webSearch = !settings.webSearch)) })
                ModelChoice("PHONE FILES", settings.localTools && settings.phoneFiles, { onSettingsChange(settings.copy(phoneFiles = !settings.phoneFiles)) })
                ModelChoice("LOCAL PY", settings.localTools && settings.localPython, { onSettingsChange(settings.copy(localPython = !settings.localPython)) })
                ModelChoice("HOSTED PY", settings.hostedPython, { onSettingsChange(settings.copy(hostedPython = !settings.hostedPython)) })
                ModelChoice("URL CONTEXT", settings.urlContext, { onSettingsChange(settings.copy(urlContext = !settings.urlContext)) })
                ModelChoice("DRAW", settings.localTools && settings.imageGeneration, { onSettingsChange(settings.copy(imageGeneration = !settings.imageGeneration)) })
                ModelChoice("ALL TOOLS", settings.localTools, { onSettingsChange(settings.copy(localTools = !settings.localTools)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("REASONING", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            thinkingLevels.forEach { level ->
                ModelChoice(level.uppercase(), settings.thinkingLevel == level,
                    { onSettingsChange(settings.copy(thinkingLevel = level)) })
            }
            Spacer(Modifier.weight(1f))
            ModelChoice("SUMMARY", settings.thinkingSummaries == "auto",
                { onSettingsChange(settings.copy(thinkingSummaries = if (settings.thinkingSummaries == "auto") "none" else "auto")) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (folderGranted) "PHONE FOLDER READY" else "PHONE FILE ACCESS",
                    color = if (folderGranted) Color(0xFF39977D) else palette.textSecondary,
                    fontFamily = OracleMono, fontSize = 8.sp)
                Text("Read and write only where you grant access.",
                    color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
            }
            OracleTextAction(if (folderGranted) "CHANGE" else "CONNECT", onChooseFolder, accent = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            listOf(4096, 8192, 16384, 32768, 65536).forEach { amount ->
                ModelChoice((amount / 1024).toString() + "K", settings.maxOutputTokens == amount,
                    { onSettingsChange(settings.copy(maxOutputTokens = amount)) })
            }
        }
    }
}

@Composable
private fun ModelChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalOraclePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> palette.accentSoft
                    pressed -> palette.surfaceRaised
                    else -> palette.canvas
                }
            )
            .border(
                1.dp,
                if (selected) palette.accent.copy(alpha = 0.45f) else palette.outlineSoft,
                RoundedCornerShape(12.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            OracleGlyphIcon(
                OracleGlyph.Check,
                Modifier.size(12.dp),
                palette.accent,
                1.8f
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) palette.accent else palette.textSecondary,
            fontFamily = OracleMono,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun modelArt(model: String): String = when {
    model.contains("flash-image", ignoreCase = true) ->
        "https://assets.runware.ai/og/google-nano-banana-2.png"
    model.contains("3.8", ignoreCase = true) ->
        "https://iweaver-sv-1402138190.cos.na-siliconvalley.myqcloud.com/docx/2026/09/03/8806aaac-cfb7-464a-a254-3ea0d9231c5c/gemini-3-8-flash.webp"
    model.contains("4-", ignoreCase = true) || model.contains("gemini-4", ignoreCase = true) ->
        "https://lh3.googleusercontent.com/O-rDY-Ewk6zMN3iWhEOHonHtrN1_xmxVJy3LZZVRdZkV7KaWo_MUX_UsCYyGOgj_9HPHCMhep1zoObX8fGpfsUyYriw8KHkjWfSo5JCeyERADq4FFQ=w1440-rw-lo"
    else ->
        "https://puzzleout.net/_emdash/api/media/file/01M1QNY5YJ98KNT5ZS29MDZXA0.png"
}

private fun modelDescription(model: GeminiModel): String = model.description?.takeIf { it.isNotBlank() } ?: when {
    model.name.contains("flash-image", true) -> "A dedicated image-generation model for creating and editing high-quality visual assets. It returns text and images but does not support function calling; ORACLE's main agent can call it through DRAW."
    model.name.contains("3.8-flash", true) -> "A versatile agent model for coding, long-running tasks, advanced reasoning and multimodal understanding."
    model.name.contains("flash-lite", true) -> "A lighter, throughput-oriented model for fast everyday work and high-volume tasks."
    model.name.contains("pro", true) -> "A higher-capability reasoning model for demanding analysis and complex knowledge work."
    model.name.contains("4-hydrogen", true) -> "A frontier reasoning model for difficult, long-horizon problems and complex agent workflows."
    model.name.contains("4-carbon", true) -> "A powerful general model with strong reasoning and multimodal capabilities."
    model.name.contains("audio", true) || model.name.contains("live", true) -> "A model family optimized for realtime audio input and conversational output."
    else -> "A Gemini model available to the connected API key. Its capabilities and limits are determined by Google's model metadata."
}

private fun modelTags(model: GeminiModel): List<String> = buildList {
    if (model.inputTokenLimit != null) add(if (model.inputTokenLimit >= 1_000_000) "1M CONTEXT" else "%,d TOKENS".format(model.inputTokenLimit))
    if (model.outputTokenLimit != null) add("%,d OUTPUT".format(model.outputTokenLimit))
    if (model.name.contains("3.8", true) || model.name.contains("4-", true)) add("AGENTIC")
    if (model.name.contains("flash", true)) add("FAST")
    if (model.name.contains("pro", true) || model.name.contains("hydrogen", true)) add("REASONING")
    if (model.name.contains("audio", true) || model.name.contains("live", true)) add("AUDIO")
    if (model.name.contains("image", true)) add("IMAGE GEN")
    if (isEmpty()) add("GEMINI API")
}

@Composable
private fun ModelBrowserDialog(
    models: List<GeminiModel>,
    selectedModel: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSelect: (GeminiModel) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.95f),
            shape = RoundedCornerShape(25.dp),
            color = Color(0xFFF4F5F9),
            shadowElevation = 22.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(190.dp)) {
                    AsyncImage(
                        model = "https://lh3.googleusercontent.com/RS81idLV6JRFjwwmuD4bLlWWaFoL0zT_syXKzTqG9wZtUG2Y6ky8cdh0ksrC4Pwc6HfEqoasrK_9L-DEZVpbioahVBYivjVMmgHm-_LoU_oG1PuyUA=w1440-rw-lo",
                        contentDescription = "Google Gemini model family artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(Modifier.fillMaxSize().background(Color(0x5507111F)).padding(17.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ORACLE  /  MODEL ATLAS", color = Color.White, fontFamily = OracleMono,
                                fontSize = 9.sp, letterSpacing = 1.0.sp)
                            Spacer(Modifier.weight(1f))
                            OracleIconButton(OracleGlyph.Close, "Close model browser", onDismiss,
                                tint = Color.White, size = 36.dp)
                        }
                        Column {
                            Text("Choose your engine.", color = Color.White, fontFamily = OracleSans,
                                fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
                            Text("Models exposed by your connected Gemini API key.",
                                color = Color(0xFFD8E0F0), fontFamily = OracleSans, fontSize = 11.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(models.size.toString() + " AVAILABLE MODELS", color = palette.textTertiary,
                        fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
                    Spacer(Modifier.weight(1f))
                    OracleTextAction(if (refreshing) "SYNCING…" else "REFRESH",
                        onRefresh, enabled = !refreshing, accent = true)
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)
                ) {
                    items(models, key = { it.name }) { candidate ->
                        val active = candidate.name == selectedModel
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .border(1.dp, if (active) Color(0x777B8FE8) else Color(0x1F25324A), RoundedCornerShape(16.dp))
                                .padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = modelArt(candidate.name),
                                    contentDescription = candidate.displayName + " model artwork",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(candidate.displayName, color = Color(0xFF20283A),
                                        fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(candidate.name, color = Color(0xFF788195),
                                        fontFamily = OracleMono, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(modelDescription(candidate), color = Color(0xFF596273),
                                        fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                modelTags(candidate).forEach { tag ->
                                    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFEFF1F8))
                                        .padding(horizontal = 7.dp, vertical = 5.dp)) {
                                        Text(tag, color = Color(0xFF6878B5), fontFamily = OracleMono,
                                            fontSize = 8.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (active) Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFE4F3EC))
                                    .padding(horizontal = 7.dp, vertical = 5.dp)) {
                                    Text("ACTIVE", color = Color(0xFF2D8A68), fontFamily = OracleMono, fontSize = 8.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (candidate.inputTokenLimit?.let { "%,d in".format(it) } ?: "Input limit n/a") +
                                        "   ·   " + (candidate.outputTokenLimit?.let { "%,d out".format(it) } ?: "Output n/a"),
                                    Modifier.weight(1f), color = Color(0xFF848C9C), fontFamily = OracleMono, fontSize = 8.sp
                                )
                                OracleSolidAction(
                                    label = if (active) "SELECTED" else "USE MODEL",
                                    onClick = { onSelect(candidate); onDismiss() },
                                    enabled = !active,
                                    modifier = Modifier.widthIn(min = 100.dp)
                                )
                            }
                        }
                    }
                }
                Text("Model availability and supported features are determined by the connected API key.",
                    Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp),
                    color = palette.textTertiary, fontFamily = OracleSans, fontSize = 9.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AgentFileEditorDialog(
    fileName: String,
    mimeType: String,
    content: String,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(21.dp), color = palette.canvas, shadowElevation = 22.dp) {
            Column(Modifier.fillMaxSize().imePadding().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GENERATED ARTIFACT", color = palette.accent, fontFamily = OracleMono,
                            fontSize = 8.sp, letterSpacing = 0.9.sp)
                        Text(fileName, color = palette.text, fontFamily = OracleSans,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(mimeType, color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp)
                    }
                    OracleIconButton(OracleGlyph.Close, "Close file editor", onDismiss, tint = palette.textSecondary)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.outlineSoft))
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OracleSpinner(Modifier.size(23.dp))
                    }
                    else -> BasicTextField(
                        value = content,
                        onValueChange = onContentChange,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF171D29)).padding(12.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFFE1E7F2),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp, lineHeight = 17.sp
                        ),
                        cursorBrush = SolidColor(Color(0xFF9AAAF4)),
                        decorationBox = { inner -> Box(Modifier.fillMaxSize()) { inner() } }
                    )
                }
                error?.let { Text(it, color = palette.error, fontFamily = OracleSans, fontSize = 11.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    OracleTextAction("CLOSE", onDismiss, enabled = !saving, accent = false)
                    Spacer(Modifier.weight(1f))
                    OracleSolidAction(
                        label = if (saving) "SAVING…" else "SAVE CHANGES",
                        onClick = onSave, enabled = !loading && !saving,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OracleThreadDrawer(
    sessions: List<studio.oracle.data.local.AgentSessionEntity>,
    activeSessionId: String?,
    workspaces: List<WorkspaceEntity>,
    activeWorkspaceId: String,
    onWorkspaceChange: (String) -> Unit,
    onCreateWorkspace: suspend (String) -> WorkspaceCreateResult,
    onClose: () -> Unit,
    onNewThread: () -> Unit,
    onSelectThread: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onOpenAudio: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var workspaceListExpanded by remember { mutableStateOf(false) }
    var workspaceComposerExpanded by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var workspaceSaving by remember { mutableStateOf(false) }
    val activeWorkspace = workspaces.firstOrNull { it.id == activeWorkspaceId }
    val todayStart = remember {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val visibleSessions = remember(sessions, searchQuery) {
        sessions.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val today = visibleSessions.filter { it.updatedAt >= todayStart }
    val yesterday = visibleSessions.filter { it.updatedAt >= todayStart - 86_400_000L && it.updatedAt < todayStart }
    val lastSevenDays = visibleSessions.filter {
        it.updatedAt >= todayStart - 7L * 86_400_000L && it.updatedAt < todayStart - 86_400_000L
    }
    val older = visibleSessions.filter { it.updatedAt < todayStart - 7L * 86_400_000L }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x55000000)).clickable(onClick = onClose))
        AnimatedVisibility(
            visible = true, modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.77f).widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                    .background(Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, end = 15.dp, top = 16.dp, bottom = 11.dp)
            ) {
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFF3F3F3)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OracleGlyphIcon(OracleGlyph.Search, Modifier.size(22.dp), Color(0xFF8A8A8A), 1.6f)
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) Text("Search chat content...",
                            color = Color(0xFF929292), fontFamily = OracleSans, fontSize = 14.sp)
                        BasicTextField(
                            value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true,
                            textStyle = TextStyle(color = Color(0xFF171717),
                                fontFamily = OracleSans, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color(0xFF557BDD)), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", color = Color(0xFF8C8C8C), fontFamily = OracleSans,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(visibleSessions.size.toString(), color = Color(0xFFAAAAAA),
                        fontFamily = OracleSans, fontSize = 11.sp)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(top = 9.dp, bottom = 12.dp)) {
                    if (today.isNotEmpty()) {
                        item { DrawerDateLabel("Today") }
                        items(today, key = { it.id }) { session ->
                            DrawerSessionRow(session.title, session.id == activeSessionId) { onSelectThread(session.id) }
                        }
                    }
                    if (yesterday.isNotEmpty()) {
                        item { DrawerDateLabel("Yesterday") }
                        items(yesterday, key = { it.id }) { session ->
                            DrawerSessionRow(session.title, session.id == activeSessionId) { onSelectThread(session.id) }
                        }
                    }
                    if (lastSevenDays.isNotEmpty()) {
                        item { DrawerDateLabel("7 Days") }
                        items(lastSevenDays, key = { it.id }) { session ->
                            DrawerSessionRow(session.title, session.id == activeSessionId) { onSelectThread(session.id) }
                        }
                    }
                    if (older.isNotEmpty()) {
                        item { DrawerDateLabel("Older") }
                        items(older, key = { it.id }) { session ->
                            DrawerSessionRow(session.title, session.id == activeSessionId) { onSelectThread(session.id) }
                        }
                    }
                    if (visibleSessions.isEmpty()) {
                        item { Text(if (searchQuery.isBlank()) "No conversations yet" else "No matching conversations",
                            Modifier.padding(horizontal = 7.dp, vertical = 15.dp),
                            color = Color(0xFF969696), fontFamily = OracleSans, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { workspaceListExpanded = !workspaceListExpanded }
                    .padding(horizontal = 3.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF171717)),
                        contentAlignment = Alignment.Center) {
                        Text("L", color = Color.White, fontFamily = OracleSans,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Littlehomemadestudio", color = Color(0xFF171717),
                            fontFamily = OracleSans, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(activeWorkspace?.name ?: "Workspace", color = Color(0xFF8B8B8B),
                            fontFamily = OracleSans, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("•••", color = Color(0xFF999999), fontSize = 16.sp)
                }
                AnimatedVisibility(
                    visible = workspaceListExpanded || workspaceComposerExpanded,
                    enter = fadeIn(tween(120)) + expandVertically(tween(160)),
                    exit = fadeOut(tween(90)) + shrinkVertically(tween(130))
                ) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                        .background(Color(0xFFF5F5F5)).padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        DrawerAction(OracleGlyph.Library, "Library", onOpenLibrary)
                        DrawerAction(OracleGlyph.Spark, "Workflow Studio", onOpenWorkflows)
                        DrawerAction(OracleGlyph.Audio, "Audio Memory", onOpenAudio)
                        DrawerAction(OracleGlyph.Settings, "Settings", onOpenSettings)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE7E7E7)))
                        workspaces.forEach { workspace ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (workspace.id == activeWorkspaceId) Color(0xFFE8EFFF) else Color.Transparent)
                                .clickable {
                                    onWorkspaceChange(workspace.id)
                                    workspaceListExpanded = false
                                    onClose()
                                }.padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(workspace.name, Modifier.weight(1f),
                                    color = if (workspace.id == activeWorkspaceId) Color(0xFF5278D8) else Color(0xFF333333),
                                    fontFamily = OracleSans, fontSize = 12.sp)
                                if (workspace.id == activeWorkspaceId) Text("ACTIVE",
                                    color = Color(0xFF5278D8), fontFamily = OracleSans, fontSize = 9.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (workspaceComposerExpanded) {
                                BasicTextField(
                                    value = workspaceName,
                                    onValueChange = { workspaceName = it.take(WorkspacePolicy.MAX_NAME_LENGTH); workspaceError = null },
                                    modifier = Modifier.weight(1f).background(Color.White, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 9.dp, vertical = 9.dp),
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color(0xFF171717),
                                        fontFamily = OracleSans, fontSize = 12.sp),
                                    cursorBrush = SolidColor(Color(0xFF5278D8)),
                                    decorationBox = { inner ->
                                        Box {
                                            if (workspaceName.isBlank()) Text("Workspace name",
                                                color = Color(0xFF999999), fontSize = 12.sp)
                                            inner()
                                        }
                                    }
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(if (workspaceSaving) "…" else "Create",
                                    Modifier.clickable(enabled = workspaceName.isNotBlank() && !workspaceSaving) {
                                        scope.launch {
                                            workspaceSaving = true
                                            try {
                                                when (val result = onCreateWorkspace(workspaceName)) {
                                                    is WorkspaceCreateResult.Success -> {
                                                        onWorkspaceChange(result.workspace.id)
                                                        workspaceName = ""
                                                        workspaceComposerExpanded = false
                                                        workspaceListExpanded = false
                                                        onClose()
                                                    }
                                                    is WorkspaceCreateResult.Failure -> workspaceError = result.message
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                workspaceError = "Could not create this workspace."
                                            } finally {
                                                workspaceSaving = false
                                            }
                                        }
                                    }.padding(horizontal = 6.dp, vertical = 8.dp),
                                    color = Color(0xFF5278D8), fontFamily = OracleSans,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("New workspace", Modifier.clickable {
                                    workspaceComposerExpanded = true
                                    workspaceListExpanded = true
                                }.padding(6.dp), color = Color(0xFF5278D8),
                                    fontFamily = OracleSans, fontSize = 12.sp)
                            }
                        }
                        workspaceError?.let {
                            Text(it, color = Color(0xFFB43B32), fontFamily = OracleSans, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerDateLabel(label: String) {
    Text(label, Modifier.padding(start = 3.dp, top = 15.dp, bottom = 7.dp),
        color = Color(0xFF8D8D8D), fontFamily = OracleSans, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DrawerSessionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFEDF2FE) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f),
            color = if (selected) Color(0xFF5278D8) else Color(0xFF171717),
            fontFamily = OracleSans, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Text("•••", color = Color(0xFF8D8D8D), fontSize = 15.sp)
    }
}

@Composable
private fun DrawerInlineAction(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    val palette = LocalOraclePalette.current
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 5.dp),
        color = if (enabled && accent) palette.accent else palette.textTertiary,
        fontFamily = OracleMono,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun DrawerAction(
    glyph: OracleGlyph,
    label: String,
    onClick: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleGlyphIcon(glyph, Modifier.size(18.dp), palette.textSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = palette.textSecondary,
            fontFamily = OracleSans,
            fontSize = 13.sp
        )
    }
}


@Composable
private fun OracleEmptyState(onPrompt: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    val greeting = remember {
        listOf(
            "Hi. What can I do for you?",
            "Hello. What are we working on today?",
            "What would you like to explore?",
            "Ready when you are.",
            "How can I help?"
        ).random()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OracleWhaleMark(
            modifier = Modifier.size(width = 38.dp, height = 30.dp),
            color = Color(0xFF4F78E8)
        )
        Spacer(Modifier.height(17.dp))
        Text(
            greeting,
            color = Color(0xFF171717),
            fontFamily = OracleSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center
        )
    }
}
