package studio.oracle.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.oracle.data.AgentAttachmentRef
import studio.oracle.data.AgenticEventCodec
import studio.oracle.data.AgentMediaInput
import studio.oracle.data.CreatedAgentFile
import java.io.File
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.AgentConversationPolicy
import studio.oracle.data.AgenticEvent
import studio.oracle.data.AgenticResult
import studio.oracle.data.AgenticSettings
import studio.oracle.data.GeminiAgenticRuntime
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
    val agenticRuntime = remember { GeminiAgenticRuntime() }
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
    var availableModels by remember {
        mutableStateOf(listOf(GeminiModel(ModelPreferences.DEFAULT_MODEL, "Gemini 3.8 Flash")))
    }
    var syncingModels by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedPrompt by remember { mutableStateOf<String?>(null) }
    var failedPromptPersisted by remember { mutableStateOf(false) }
    var suppressCancelledPromptRestore by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var modelPanelOpen by remember { mutableStateOf(false) }
    var modelBrowserOpen by remember { mutableStateOf(false) }
    var agenticSettings by remember { mutableStateOf(AgenticSettings()) }
    var agentEvents by remember { mutableStateOf(emptyList<AgenticEvent>()) }
    var selectedAttachments by remember { mutableStateOf(emptyList<AgentAttachmentRef>()) }
    var sessionToolsApproved by remember { mutableStateOf(false) }
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

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            grantedTreeUri = uri
            context.getSharedPreferences("oracle_agent_permissions", Context.MODE_PRIVATE)
                .edit().putString("phone_tree_uri", uri.toString()).apply()
        }.onFailure { error = "Android could not persist access to that folder. Choose it again." }
    }
    suspend fun authorizeTool(action: String, permissionGroup: String): Boolean {
        if (sessionToolsApproved) return true
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main.immediate) {
            pendingApproval = action
            approvalDeferred = deferred
        }
        return try {
            when (deferred.await()) {
                "session" -> {
                    withContext(Dispatchers.Main.immediate) { sessionToolsApproved = true }
                    true
                }
                "once" -> true
                else -> false
            }
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
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return
        scope.launch {
            loadingFile = true
            fileEditorError = null
            editingFileUri = uri
            try {
                editingFileName = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Generated file"
                editingFileMime = context.contentResolver.getType(uri) ?: "text/plain"
                editingFileContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        require(bytes.size <= 2 * 1024 * 1024) { "In-app editor supports text files up to 2 MB." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not open this file.")
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
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(editingFileContent.toByteArray(Charsets.UTF_8))
                    } ?: error("Android could not save this file.")
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
        messages = emptyList()
        agentEvents = emptyList()
        prompt = ""
        error = null
        failedPrompt = null
        failedPromptPersisted = false
        try {
            activeSessionId = repository.ensureSession(model)
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
            val runEvents = java.util.Collections.synchronizedList(mutableListOf<AgenticEvent>())
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

                val mediaInputs = try {
                    withContext(Dispatchers.IO) {
                        var totalBytes = 0L
                        attachmentSnapshot.map { attachment ->
                            val uri = Uri.parse(attachment.uri)
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("Could not read " + attachment.name)
                            totalBytes += bytes.size
                            require(bytes.size <= 20 * 1024 * 1024 && totalBytes <= 30 * 1024 * 1024) {
                                "Attachments exceed the 30 MB combined request limit. Send fewer or smaller files."
                            }
                            AgentMediaInput(
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            )
                        }
                    }
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
                    repository.appendMessage(
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
                val agentPrompt = if (priorContext.isBlank()) submitted else
                    "Conversation history (use as context):\n" + priorContext + "\n\nCurrent user request:\n" + submitted
                val tavilyKey = withContext(Dispatchers.IO) { runCatching { keyStore.readTavilyKey() }.getOrNull() }
                val executor = PhoneWorkspaceTools(
                    context = context,
                    grantedTree = grantedTreeUri,
                    tavilyKey = tavilyKey,
                    authorize = ::authorizeTool,
                    python = { code -> pythonRuntime.execute(code) },
                    onFileCreated = { file ->
                        val event = AgenticEvent(
                            kind = "file_created",
                            title = "File created",
                            detail = file.name + " · " + file.mimeType,
                            payload = file.uri
                        )
                        runEvents.add(event)
                        scope.launch(Dispatchers.Main.immediate) {
                            agentEvents = (agentEvents + event).takeLast(60)
                        }
                    }
                )

                when (val result = agenticRuntime.run(
                    apiKey = key,
                    prompt = agentPrompt,
                    model = model,
                    settings = agenticSettings,
                    executor = executor,
                    media = mediaInputs,
                    onEvent = { sourceEvent ->
                        val event = if (sourceEvent.kind == "generated_image" && sourceEvent.payload.isNotBlank()) {
                            runCatching {
                                val directory = File(context.filesDir, "oracle-generated").apply { mkdirs() }
                                val bytes = Base64.decode(sourceEvent.payload, Base64.DEFAULT)
                                val extension = if (sourceEvent.detail.contains("jpeg", true)) "jpg" else "png"
                                val file = File(directory, UUID.randomUUID().toString() + "." + extension)
                                file.writeBytes(bytes)
                                sourceEvent.copy(payload = file.absolutePath)
                            }.getOrDefault(sourceEvent.copy(detail = "Image output could not be saved locally."))
                        } else sourceEvent
                        runEvents.add(event)
                        scope.launch(Dispatchers.Main.immediate) {
                            agentEvents = (agentEvents + event).takeLast(60)
                        }
                    }
                )) {
                    is AgenticResult.Success -> {
                        val trace = synchronized(runEvents) { runEvents.toList() }
                        repository.appendMessage(
                            sessionId = sessionId,
                            role = "assistant",
                            content = result.text,
                            model = result.model,
                            agentTrace = AgenticEventCodec.encodeTrace(trace)
                        )
                        failedPrompt = null
                        failedPromptPersisted = false
                    }
                    is AgenticResult.Failed -> {
                        error = result.message
                        failedPrompt = submitted
                        failedPromptPersisted = userMessagePersisted
                        selectedAttachments = attachmentSnapshot
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!suppressCancelledPromptRestore) {
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submittedText
                    selectedAttachments = attachmentSnapshot
                }
                throw cancelled
            } catch (exception: Exception) {
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
                onNewThread = { createNewThread() }
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

            DeepSeekInputPill(
                value = prompt,
                onValueChange = { prompt = it },
                busy = busy,
                onSend = { submitPrompt() },
                onCancel = { requestJob?.cancel() },
                onThinkClick = { modelPanelOpen = !modelPanelOpen; keyboard?.hide() },
                thinkExpanded = modelPanelOpen,
                attachments = selectedAttachments,
                onAttachClick = { mediaPicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = { attachment -> selectedAttachments = selectedAttachments.filterNot { it.uri == attachment.uri } },
                modifier = Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
            )

            AnimatedVisibility(
                visible = modelPanelOpen,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically()
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
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                )
            }

            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(4.dp)))
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
                }
            )
        }
        if (pendingApproval != null) {
            AlertDialog(
                onDismissRequest = { approvalDeferred?.complete(false); approvalDeferred = null; pendingApproval = null },
                title = { Text("ORACLE wants permission") },
                text = { Text(pendingApproval.orEmpty()) },
                confirmButton = { TextButton(onClick = { approvalDeferred?.complete(true); approvalDeferred = null; pendingApproval = null }) { Text("ALLOW ONCE") } },
                dismissButton = { TextButton(onClick = { approvalDeferred?.complete(false); approvalDeferred = null; pendingApproval = null }) { Text("DENY") } }
            )
        }
    }
}

@Composable
private fun CustomTopBar(
    onMenu: () -> Unit,
    onNewThread: () -> Unit
) {
    val palette = LocalOraclePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OracleIconButton(
            glyph = OracleGlyph.Menu,
            description = "Open conversations",
            onClick = onMenu,
            tint = palette.text
        )
        Spacer(Modifier.width(7.dp))
        HandcraftedLogo(
            modifier = Modifier.size(27.dp),
            color = palette.text
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "ORACLE",
            color = palette.text,
            fontFamily = OracleSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.weight(1f))
        OracleIconButton(
            glyph = OracleGlyph.NewChat,
            description = "New conversation",
            onClick = onNewThread,
            tint = palette.textSecondary
        )
    }
}

@Composable
private fun OracleMessageRow(
    message: AgentMessageEntity,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    val attachments = remember(message.attachments) { AgenticEventCodec.decodeAttachments(message.attachments) }
    val events = remember(message.agentTrace) { AgenticEventCodec.decodeTrace(message.agentTrace) }
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { it / 18 },
        exit = fadeOut(tween(130))
    ) {
        if (message.role == "user") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(
                    Modifier.widthIn(max = 340.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(palette.surfaceRaised)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (message.content.isNotBlank()) Text(
                        message.content,
                        color = palette.text, fontFamily = OracleSans, fontSize = 15.sp, lineHeight = 23.sp
                    )
                    attachments.forEach { attachment ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(palette.surface).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    attachment.mimeType.startsWith("image/") -> "▧"
                                    attachment.mimeType.startsWith("video/") -> "▶"
                                    attachment.mimeType.startsWith("audio/") -> "♫"
                                    else -> "▤"
                                },
                                color = palette.accent, fontSize = 14.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.name, color = palette.text, fontSize = 11.sp,
                                    fontFamily = OracleSans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(attachment.mimeType, color = palette.textTertiary,
                                    fontSize = 9.sp, fontFamily = OracleMono)
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                HandcraftedLogo(Modifier.padding(top = 3.dp).size(22.dp), palette.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ORACLE", color = palette.text, fontFamily = OracleMono,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                        Spacer(Modifier.weight(1f))
                        events.firstOrNull { it.kind == "usage" }?.let { TokenUsageChip(it.detail) }
                    }
                    if (events.isNotEmpty()) AgentExecutionTrace(events, onOpenFile)
                    OracleRichMessage(message.content)
                }
            }
        }
    }
}

@Composable
private fun TokenUsageChip(detail: String) {
    val input = Regex("input\\s+(\\d+)").find(detail)?.groupValues?.getOrNull(1)
    val output = Regex("output\\s+(\\d+)").find(detail)?.groupValues?.getOrNull(1)
    val total = (input?.toIntOrNull() ?: 0) + (output?.toIntOrNull() ?: 0)
    val label = if (total > 0) if (total >= 1000) "%.1fk".format(total / 1000f) else total.toString() else "i"
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(Color(0x187B8FE8)).border(1.dp, Color(0x397B8FE8), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF7488E2), fontFamily = OracleMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AgentExecutionTrace(events: List<AgenticEvent>, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    var expanded by remember(events.size, events.lastOrNull()?.title) { mutableStateOf(false) }
    val visibleEvents = events.filter { it.kind != "usage" }
    val actionCount = visibleEvents.count { it.kind.endsWith("start") || it.kind == "file_created" }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Color(0x147B8FE8)).clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).height(18.dp).background(Color(0xFF788CE5), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(9.dp))
            Text("WORK LOG", color = Color(0xFF7E91EA), fontFamily = OracleMono,
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.85.sp)
            Spacer(Modifier.width(8.dp))
            Text(actionCount.toString() + " actions · " + visibleEvents.size + " events",
                color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "HIDE  −" else "VIEW  +", color = Color(0xFF7E91EA),
                fontFamily = OracleMono, fontSize = 9.sp)
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
                visibleEvents.forEach { event -> AgentEventRow(event, onOpenFile) }
            }
        }
    }
}

@Composable
private fun AgentEventRow(event: AgenticEvent, onOpenFile: (String) -> Unit) {
    val palette = LocalOraclePalette.current
    val isFile = event.kind == "file_created"
    val isCode = event.code.isNotBlank()
    val isImage = event.kind == "generated_image"
    var expanded by remember(event.title, event.kind, event.code) { mutableStateOf(false) }
    val accent = when {
        isFile -> Color(0xFF42A68A)
        event.kind.contains("python") || event.kind.contains("hosted") -> Color(0xFFBF8B52)
        event.kind.contains("search") || event.kind.contains("url") -> Color(0xFF5C9ACB)
        else -> Color(0xFF7B8FE8)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).width(2.dp).heightIn(min = 28.dp).background(accent.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(9.dp))
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
            } else if (event.detail.isNotBlank() && !isImage) {
                Text(event.detail.take(if (expanded) 16000 else 180), color = palette.textSecondary,
                    fontFamily = if (event.kind.contains("python")) OracleMono else OracleSans,
                    fontSize = 10.sp, lineHeight = 15.sp)
            }
            if (isCode && expanded) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF171D29)).padding(9.dp)) {
                    Text(event.code.take(16000), color = Color(0xFFE0E7F2),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
            if (isImage && event.payload.isNotBlank()) {
                val bitmap = remember(event.payload) { BitmapFactory.decodeFile(event.payload) }
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = "Generated by ORACLE",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)))
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
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(25.dp))
            .background(palette.surface)
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(25.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MODEL",
                color = palette.textTertiary,
                fontFamily = OracleMono,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.weight(1f))
            OracleTextAction(
                if (refreshing) "LOADING" else "REFRESH",
                onRefresh,
                enabled = !refreshing,
                accent = true
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            models.forEach { candidate ->
                ModelChoice(label = candidate.displayName, selected = candidate.name == selectedModel, onClick = { onSelect(candidate) })
            }
        }
        val activeModel = models.firstOrNull { it.name == selectedModel }
        val inputLimit = activeModel?.inputTokenLimit
        val outputLimit = activeModel?.outputTokenLimit
        Text(
            "CONTEXT  " + (inputLimit?.let { "%,d".format(it) + " tokens" } ?: "limit unavailable") +
                "   ·   OUTPUT  " + (outputLimit?.let { "%,d".format(it) } ?: "model default"),
            color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp
        )
        Spacer(Modifier.height(12.dp))
        val thinkingLevels = when {
            selectedModel.contains("4-hydrogen") -> listOf("low", "medium", "high", "xhigh")
            selectedModel.contains("3.6-flash") || selectedModel.contains("3.5-flash-lite") ||
                selectedModel.contains("3.1-flash-lite") || selectedModel.contains("3-flash-preview") -> listOf("minimal", "low", "medium", "high")
            else -> listOf("low", "medium", "high")
        }
        Text("REASONING", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            thinkingLevels.forEach { level ->
                ModelChoice(level.uppercase(), settings.thinkingLevel == level,
                    { onSettingsChange(settings.copy(thinkingLevel = level)) })
            }
        }
        Spacer(Modifier.height(9.dp))
        Text("MODEL CAPABILITIES", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModelChoice("SEARCH", settings.googleSearch, { onSettingsChange(settings.copy(googleSearch = !settings.googleSearch)) })
            ModelChoice("HOSTED PY", settings.hostedPython, { onSettingsChange(settings.copy(hostedPython = !settings.hostedPython)) })
            ModelChoice("URL CONTEXT", settings.urlContext, { onSettingsChange(settings.copy(urlContext = !settings.urlContext)) })
            ModelChoice("LOCAL TOOLS", settings.localTools, { onSettingsChange(settings.copy(localTools = !settings.localTools)) })
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (folderGranted) "PHONE FOLDER CONNECTED" else "PHONE FILE ACCESS",
                    color = if (folderGranted) palette.success else palette.textSecondary,
                    fontFamily = OracleMono, fontSize = 9.sp)
                Text("Read and create files only inside the folder you choose.",
                    color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
            }
            OracleTextAction(if (folderGranted) "CHANGE" else "GRANT FOLDER", onChooseFolder, accent = true)
        }
        Spacer(Modifier.height(9.dp))
        Text("TOOL SELECTION", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModelChoice("AUTO", settings.toolChoice == "auto", { onSettingsChange(settings.copy(toolChoice = "auto")) })
            ModelChoice("NO TOOLS", settings.toolChoice == "none", { onSettingsChange(settings.copy(toolChoice = "none")) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THINKING SUMMARY", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            ModelChoice(if (settings.thinkingSummaries == "auto") "ON" else "OFF",
                settings.thinkingSummaries == "auto",
                { onSettingsChange(settings.copy(thinkingSummaries = if (settings.thinkingSummaries == "auto") "none" else "auto")) })
        }
        Text("Context caching is model-managed; cached tokens appear in the context ledger.",
            color = palette.textTertiary, fontFamily = OracleSans, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT LIMIT", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            listOf(4096, 8192, 16384, 32768).forEach { amount ->
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
    onOpenWorkflows: () -> Unit
) {
    val palette = LocalOraclePalette.current
    val scope = rememberCoroutineScope()
    var workspaceListExpanded by remember { mutableStateOf(false) }
    var workspaceComposerExpanded by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var workspaceSaving by remember { mutableStateOf(false) }
    val activeWorkspace = workspaces.firstOrNull { it.id == activeWorkspaceId }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.scrim)
                .clickable(onClick = onClose)
        )
        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(316.dp)
                    .background(palette.surface)
                    .padding(horizontal = 14.dp, vertical = 15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HandcraftedLogo(Modifier.size(31.dp), palette.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "ORACLE",
                        color = palette.text,
                        fontFamily = OracleSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(Modifier.weight(1f))
                    OracleIconButton(
                        OracleGlyph.Close,
                        "Close conversations",
                        onClose,
                        tint = palette.textSecondary,
                        size = 38.dp
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "WORKSPACE",
                            color = palette.textTertiary,
                            fontFamily = OracleMono,
                            fontSize = 9.sp,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            activeWorkspace?.name ?: "Loading workspace",
                            color = palette.text,
                            fontFamily = OracleSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DrawerInlineAction(
                        label = if (workspaceListExpanded) "CLOSE" else "SWITCH",
                        onClick = { workspaceListExpanded = !workspaceListExpanded }
                    )
                    Spacer(Modifier.width(8.dp))
                    DrawerInlineAction(
                        label = if (workspaceComposerExpanded) "CANCEL" else "NEW",
                        onClick = {
                            workspaceComposerExpanded = !workspaceComposerExpanded
                            workspaceError = null
                        },
                        accent = true
                    )
                }

                if (workspaceListExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(palette.canvas)
                            .verticalScroll(rememberScrollState())
                            .padding(5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        workspaces.forEach { workspace ->
                            val selected = workspace.id == activeWorkspaceId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) palette.accentSoft else Color.Transparent)
                                    .clickable {
                                        onWorkspaceChange(workspace.id)
                                        workspaceListExpanded = false
                                        onClose()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    workspace.name,
                                    modifier = Modifier.weight(1f),
                                    color = if (selected) palette.accent else palette.textSecondary,
                                    fontFamily = OracleSans,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selected) {
                                    Text("ACTIVE", color = palette.accent, fontFamily = OracleMono, fontSize = 8.sp)
                                }
                            }
                        }
                    }
                }

                if (workspaceComposerExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(palette.surfaceRaised)
                            .padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "CREATE WORKSPACE",
                            color = palette.textTertiary,
                            fontFamily = OracleMono,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                        BasicTextField(
                            value = workspaceName,
                            onValueChange = {
                                workspaceName = it.take(WorkspacePolicy.MAX_NAME_LENGTH)
                                workspaceError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(palette.canvas)
                                .padding(horizontal = 11.dp, vertical = 10.dp),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = palette.text,
                                fontFamily = OracleSans,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(palette.accent),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (workspaceName.isBlank()) {
                                        Text(
                                            "Workspace name",
                                            color = palette.textTertiary,
                                            fontFamily = OracleSans,
                                            fontSize = 13.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        if (workspaceError != null) {
                            Text(
                                workspaceError.orEmpty(),
                                color = palette.error,
                                fontFamily = OracleSans,
                                fontSize = 10.sp
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DrawerInlineAction(
                                label = "CANCEL",
                                onClick = {
                                    workspaceComposerExpanded = false
                                    workspaceName = ""
                                    workspaceError = null
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            DrawerInlineAction(
                                label = if (workspaceSaving) "SAVING" else "CREATE",
                                accent = true,
                                enabled = !workspaceSaving && workspaceName.isNotBlank(),
                                onClick = {
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
                                                is WorkspaceCreateResult.Failure -> {
                                                    workspaceError = result.message
                                                }
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            workspaceError = "Could not create this workspace. Try again."
                                        } finally {
                                            workspaceSaving = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.accentSoft)
                        .clickable(onClick = onNewThread)
                        .padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OracleGlyphIcon(OracleGlyph.NewChat, Modifier.size(18.dp), palette.accent)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "New chat",
                        color = palette.accent,
                        fontFamily = OracleSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }

                Spacer(Modifier.height(23.dp))
                Text(
                    "RECENT",
                    color = palette.textTertiary,
                    fontFamily = OracleMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val selected = session.id == activeSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (selected) palette.surfaceRaised else Color.Transparent)
                                .clickable { onSelectThread(session.id) }
                                .padding(horizontal = 11.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OracleGlyphIcon(
                                OracleGlyph.Chat,
                                Modifier.size(17.dp),
                                if (selected) palette.accent else palette.textTertiary,
                                1.4f
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                session.title.ifBlank { "New conversation" },
                                modifier = Modifier.weight(1f),
                                color = if (selected) palette.text else palette.textSecondary,
                                fontFamily = OracleSans,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                DrawerAction(OracleGlyph.Library, "Library", onOpenLibrary)
                DrawerAction(OracleGlyph.Spark, "Workflow Studio", onOpenWorkflows)
                DrawerAction(OracleGlyph.Settings, "Settings", onOpenSettings)
            }
        }
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
