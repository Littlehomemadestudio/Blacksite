package studio.oracle.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import kotlinx.coroutines.Dispatchers
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
        mutableStateOf(listOf(GeminiModel(ModelPreferences.DEFAULT_MODEL, "Gemini 3.5 Flash")))
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
    var agenticSettings by remember { mutableStateOf(AgenticSettings()) }
    var agentEvents by remember { mutableStateOf(emptyList<AgenticEvent>()) }
    var grantedTreeUri by remember {
        mutableStateOf(context.getSharedPreferences("oracle_agent_permissions", Context.MODE_PRIVATE)
            .getString("phone_tree_uri", null)?.let(Uri::parse))
    }
    var pendingApproval by remember { mutableStateOf<String?>(null) }
    var approvalDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            grantedTreeUri = uri
            context.getSharedPreferences("oracle_agent_permissions", Context.MODE_PRIVATE)
                .edit().putString("phone_tree_uri", uri.toString()).apply()
        }.onFailure { error = "Android could not persist access to that folder. Choose it again." }
    }
    suspend fun approveTool(action: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) { pendingApproval = action; approvalDeferred = deferred }
        return try { deferred.await() } finally {
            withContext(Dispatchers.Main.immediate) {
                if (approvalDeferred === deferred) { approvalDeferred = null; pendingApproval = null }
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
            prompt = ""
            clearTransientState()
            modelPanelOpen = false
            keyboard?.hide()
            if (closeDrawer) drawerOpen = false
        }
    }

    fun selectThread(sessionId: String) {
        activeSessionId = sessionId
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
        val submitted = prompt.trim()
        if (submitted.isBlank() || busy) return

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
        agentEvents = emptyList()
        error = null
        keyboard?.hide()
        busy = true

        requestJob = scope.launch {
            var userMessagePersisted = retry
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
                    prompt = submitted
                    return@launch
                }

                if (key.isBlank()) {
                    error = "Connect a Gemini API key in Settings to start chatting."
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submitted
                    return@launch
                }

                if (!retry) {
                    repository.appendMessage(sessionId, "user", submitted, model)
                    userMessagePersisted = true
                    failedPromptPersisted = true
                }

                val priorContext = AgentConversationPolicy.context(history).joinToString("\n\n") { (role, text) ->
                    (if (role == "assistant") "ORACLE" else "USER") + ": " + text
                }
                val agentPrompt = if (priorContext.isBlank()) submitted else
                    "Conversation history (use as context):\n" + priorContext + "\n\nCurrent user request:\n" + submitted
                val executor = PhoneWorkspaceTools(context, grantedTreeUri, ::approveTool) { code ->
                    pythonRuntime.execute(code)
                }
                when (val result = agenticRuntime.run(
                    apiKey = key, prompt = agentPrompt, model = model,
                    settings = agenticSettings, executor = executor,
                    onEvent = { event -> scope.launch(Dispatchers.Main.immediate) {
                        agentEvents = (agentEvents + event).takeLast(40)
                    } }
                )) {
                    is AgenticResult.Success -> {
                        repository.appendMessage(sessionId, "assistant", result.text, result.model)
                        failedPrompt = null
                        failedPromptPersisted = false
                    }
                    is AgenticResult.Failed -> {
                        error = result.message
                        failedPrompt = submitted
                        failedPromptPersisted = userMessagePersisted
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!suppressCancelledPromptRestore) {
                    failedPrompt = submitted
                    failedPromptPersisted = userMessagePersisted
                    prompt = submitted
                }
                throw cancelled
            } catch (_: Exception) {
                error = "Something went wrong. Check your connection and try again."
                failedPrompt = submitted
                failedPromptPersisted = userMessagePersisted
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
                            OracleMessageRow(message = message, modifier = Modifier.animateItem())
                        }
                        if (busy) {
                            item { OracleThinkingIndicator() }
                        }
                        if (agentEvents.isNotEmpty()) {
                            item { AgenticActivityTimeline(agentEvents) }
                        }
                        if (agentEvents.isNotEmpty()) {
                            item { AgenticActivityTimeline(agentEvents) }
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
                onThinkClick = { modelPanelOpen = !modelPanelOpen },
                thinkExpanded = modelPanelOpen,
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
private fun OracleMessageRow(message: AgentMessageEntity, modifier: Modifier = Modifier) {
    val palette = LocalOraclePalette.current
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 14 },
        exit = fadeOut(tween(100))
    ) {
        if (message.role == "user") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.surfaceRaised)
                        .padding(horizontal = 17.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = message.content,
                        color = palette.text,
                        fontFamily = OracleSans,
                        fontSize = 15.sp,
                        lineHeight = 23.sp
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                HandcraftedLogo(
                    modifier = Modifier.padding(top = 3.dp).size(21.dp),
                    color = palette.accent
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message.content,
                    modifier = Modifier.weight(1f),
                    color = palette.text,
                    fontFamily = OracleSans,
                    fontSize = 15.sp,
                    lineHeight = 24.sp
                )
            }
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
        Text("REASONING", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("low", "medium", "high", "xhigh").forEach { level ->
                ModelChoice(level.uppercase(), settings.thinkingLevel == level, { onSettingsChange(settings.copy(thinkingLevel = level)) })
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
        Text("SAMPLING", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(0.2, 0.5, 0.8, 1.0).forEach { value ->
                ModelChoice(value.toString(), settings.temperature == value, { onSettingsChange(settings.copy(temperature = value)) })
            }
            listOf(0.7, 0.85, 0.95, 1.0).forEach { value ->
                ModelChoice("P" + value.toString(), settings.topP == value, { onSettingsChange(settings.copy(topP = value)) })
            }
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
