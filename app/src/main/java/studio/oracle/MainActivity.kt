package studio.oracle

import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.AgentConversationPolicy
import studio.oracle.data.AgentRepository
import studio.oracle.data.AgentTurn
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiModel
import studio.oracle.data.GeminiModelsResult
import studio.oracle.data.ModelPreferences
import studio.oracle.data.local.AgentMessageEntity
import studio.oracle.data.local.OracleDatabase
import studio.oracle.data.GeminiConnectionResult
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.LibraryScreen
import studio.oracle.ui.WorkflowScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink=Color(0xFF202124)
private val Panel=Color(0xFFF7F8FA)
private val Raised=Color(0xFFEFF1F5)
private val Line=Color(0xFFE5E7EB)
private val Paper=Color(0xFF202124)
private val Muted=Color(0xFF777D87)
private val Accent=Color(0xFF2563EB)
private val Violet=Color(0xFF8B5CF6)
private val Glass=Color(0xDFFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OracleApp() }
    }
}

private enum class OracleSection { AGENT, LIBRARY, AUTOMATIONS, SETTINGS }

@Composable
private fun OracleApp() {
    var prompt by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(OracleSection.AGENT) }

    BackHandler(enabled = section != OracleSection.AGENT) {
        section = OracleSection.AGENT
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color(0xFFF8F9FB),
            surface = Color.White,
            surfaceVariant = Panel,
            primary = Accent,
            secondary = Violet,
            onBackground = Paper,
            onSurface = Paper,
            onSurfaceVariant = Muted
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F9FB)) {
            when (section) {
                OracleSection.AGENT -> AgentScreen(
                    prompt = prompt,
                    onPrompt = { prompt = it },
                    onSettings = { section = OracleSection.SETTINGS },
                    onLibrary = { section = OracleSection.LIBRARY },
                    onAutomations = { section = OracleSection.AUTOMATIONS }
                )

                OracleSection.LIBRARY -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(54.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { section = OracleSection.AGENT }) {
                            Icon(Icons.Default.ArrowBack, "Back to ORACLE")
                        }
                        Text("Document Library", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    LibraryScreen()
                }

                OracleSection.AUTOMATIONS -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(54.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { section = OracleSection.AGENT }) {
                            Icon(Icons.Default.ArrowBack, "Back to ORACLE")
                        }
                        Text("Workflow Studio", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    WorkflowScreen()
                }

                OracleSection.SETTINGS -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(54.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { section = OracleSection.AGENT }) {
                            Icon(Icons.Default.ArrowBack, "Back to ORACLE")
                        }
                        Text("Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun AgentScreen(
    prompt: String,
    onPrompt: (String) -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onAutomations: () -> Unit
) {
    val context = LocalContext.current
    val keyStore = remember { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val repository = remember(context) {
        AgentRepository(OracleDatabase.get(context).agentDao())
    }
    val modelPreferences = remember(context) { ModelPreferences(context) }
    val scope = rememberCoroutineScope()

    val sessions by repository.sessions.collectAsState(initial = emptyList())
    val model by modelPreferences.selectedModel.collectAsState(initial = ModelPreferences.DEFAULT_MODEL)
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(emptyList<AgentMessageEntity>()) }
    var availableModels by remember {
        mutableStateOf(listOf(GeminiModel(ModelPreferences.DEFAULT_MODEL, "Gemini 2.5 Flash")))
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var syncingModels by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("READY / LOCAL SESSION") }
    var error by remember { mutableStateOf<String?>(null) }
    var failedPrompt by remember { mutableStateOf<String?>(null) }
    var failedPromptPersisted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        activeSessionId = repository.ensureSession(model)
    }
    LaunchedEffect(sessions) {
        val current = activeSessionId
        if (current == null && sessions.isNotEmpty()) {
            activeSessionId = sessions.first().id
        } else if (current != null && sessions.none { it.id == current }) {
            activeSessionId = sessions.firstOrNull()?.id
        }
    }
    LaunchedEffect(activeSessionId) {
        val id = activeSessionId
        if (id == null) {
            messages = emptyList()
        } else {
            repository.messages(id).collect { messages = it }
        }
    }

    fun refreshModels() {
        if (syncingModels) return
        scope.launch {
            syncingModels = true
            error = null
            status = "LOADING AVAILABLE GEMINI MODELS"
            try {
                val key = keyStore.read().orEmpty()
                when (val result = api.listModels(key)) {
                    is GeminiModelsResult.Success -> {
                        availableModels = result.models
                        status = if (result.models.isEmpty()) {
                            "NO GENERATECONTENT MODELS AVAILABLE"
                        } else {
                            "LOADED " + result.models.size + " MODELS"
                        }
                        if (result.models.isEmpty()) {
                            error = "This key exposes no models that support generateContent."
                        }
                    }
                    is GeminiModelsResult.Failed -> {
                        error = result.message
                        status = "MODEL DISCOVERY FAILED"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not load models. Check the stored key and network connection."
                status = "MODEL DISCOVERY FAILED"
            } finally {
                syncingModels = false
            }
        }
    }

    val menuModels = remember(availableModels, model) {
        (availableModels + GeminiModel(model, model)).distinctBy { it.name }
    }

    var toolsExpanded by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    val xpBlue = Color(0xFF0A58CA)
    val xpDeep = Color(0xFF003C9D)
    val xpPale = Color(0xFFEAF3FF)
    val xpSilver = Color(0xFFE8E8E8)
    val xpLine = Color(0xFF7F9DB9)

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color(0x66000000),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight().width(292.dp),
                drawerContainerColor = Color(0xFFECE9D8),
                drawerShape = RoundedCornerShape(0.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(7.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Brush.horizontalGradient(listOf(Color(0xFF3A82E6), Color(0xFF0750B8), Color(0xFF003B91))))
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(34.dp).background(Color(0xFFFFD45B), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                            Text("O", color = Color(0xFF174A91), fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("ORACLE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                            Text("Personal intelligence", color = Color(0xFFDCEBFF), fontSize = 10.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        XPWindowButton("×", onClick = { uiScope.launch { drawer.close() } }, close = true, description = "Close chat history")
                    }
                    Spacer(Modifier.height(9.dp))
                    XPButton("＋   New conversation", Modifier.fillMaxWidth(), onClick = {
                        uiScope.launch {
                            activeSessionId = repository.createSession(model)
                            error = null
                            failedPrompt = null
                            failedPromptPersisted = false
                            status = "READY / LOCAL SESSION"
                            drawer.close()
                        }
                    }, primary = true)
                    Spacer(Modifier.height(13.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CHAT HISTORY", color = Color(0xFF53677D), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${sessions.size}", color = Color(0xFF65798D), fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    HorizontalDivider(color = Color(0xFFB6C6D5))
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF8FAFC))
                            .border(BorderStroke(1.dp, Color(0xFF9DB4C9))),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            val selected = session.id == activeSessionId
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (selected) Color(0xFFD8E9FC) else Color.Transparent)
                                    .clickable {
                                        activeSessionId = session.id
                                        error = null
                                        failedPrompt = null
                                        failedPromptPersisted = false
                                        status = "READY / LOCAL SESSION"
                                        uiScope.launch { drawer.close() }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ChatBubbleOutline, null,
                                    tint = if (selected) Color(0xFF0756B8) else Color(0xFF667D92),
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    session.title.ifBlank { "New conversation" },
                                    color = Color(0xFF24384B), fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    XPButton("⌘   Workflow Studio", Modifier.fillMaxWidth(), onClick = {
                        onAutomations()
                        uiScope.launch { drawer.close() }
                    })
                    Spacer(Modifier.height(5.dp))
                    XPButton("⚙   Settings", Modifier.fillMaxWidth(), onClick = {
                        onSettings()
                        uiScope.launch { drawer.close() }
                    })
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF7BA7D8)).padding(5.dp)) {
            // Luna-style application title bar.
            Row(
                Modifier.fillMaxWidth().height(43.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF3D8CEB), Color(0xFF1265C7), Color(0xFF0750A9), Color(0xFF003B87))))
                    .padding(start = 8.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(27.dp).background(Color(0xFFFFD45B), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                    Text("O", color = Color(0xFF174A91), fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("ORACLE Studio", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Personal intelligence workspace", color = Color(0xFFD9E9FF), fontSize = 9.sp)
                }
                XPWindowButton("—", onClick = { uiScope.launch { drawer.open() } }, description = "Open chat history")
                Spacer(Modifier.width(3.dp))
                XPWindowButton("□", onClick = { toolsExpanded = !toolsExpanded }, description = "Toggle model tools")
                Spacer(Modifier.width(3.dp))
                XPWindowButton("×", onClick = { onPrompt("") }, close = true, description = "Clear message draft")
            }

            // Classic menu / toolbar strip.
            Row(
                Modifier.fillMaxWidth().height(34.dp)
                    .background(Color(0xFFECE9D8))
                    .border(BorderStroke(1.dp, Color.White))
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPMenuItem("Chats", onClick = { uiScope.launch { drawer.open() } })
                XPMenuItem("Library", onClick = onLibrary)
                XPMenuItem("Flows", onClick = onAutomations)
                XPMenuItem("New", onClick = {
                    uiScope.launch {
                        activeSessionId = repository.createSession(model)
                        error = null
                        failedPrompt = null
                        failedPromptPersisted = false
                        status = "READY / LOCAL SESSION"
                    }
                })
                Spacer(Modifier.weight(1f))
                Text(
                    if (busy) "WORKING…" else status,
                    color = when {
                        busy -> Color(0xFF0756B8)
                        status.contains("FAILED") || status.contains("CANCELLED") || status.contains("ERROR") -> Color(0xFF9E2921)
                        else -> Color(0xFF54708A)
                    },
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .45.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 7.dp)
                )
            }

            // Main conversation surface.
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFFFDFDFD))
                    .border(BorderStroke(1.dp, Color(0xFF557FA9))
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().height(30.dp)
                        .background(Color(0xFFDCEBFA))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Forum, null, tint = Color(0xFF245B91), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        sessions.firstOrNull { it.id == activeSessionId }?.title?.ifBlank { "Conversation" } ?: "Conversation",
                        color = Color(0xFF294C70), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    Text("●", color = Color(0xFF36A852), fontSize = 8.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("LOCAL DATA", color = Color(0xFF54708A), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Color(0xFFB5C9DD))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(62.dp).background(
                                    Brush.verticalGradient(listOf(Color(0xFFFFF2B8), Color(0xFFE6B94E))),
                                    RoundedCornerShape(5.dp)
                                ).border(BorderStroke(1.dp, Color(0xFFB38A2E)), RoundedCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("O", color = Color(0xFF174A91), fontSize = 42.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Welcome to ORACLE", color = Color(0xFF25496D), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                            Spacer(Modifier.height(5.dp))
                            Text("Your workspace is ready.", color = Color(0xFF63788C), fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(15.dp),
                            contentPadding = PaddingValues(top = 15.dp, bottom = 18.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                if (message.role == "user") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Surface(
                                            color = Color(0xFFE7F1FC),
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, Color(0xFFB5CDE5)),
                                            modifier = Modifier.widthIn(max = 310.dp)
                                        ) {
                                            Text(
                                                message.content,
                                                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                color = Color(0xFF1D344B), fontSize = 14.sp, lineHeight = 21.sp
                                            )
                                        }
                                    }
                                } else {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Box(
                                            Modifier.size(24.dp).background(Color(0xFFFFE7A0), RoundedCornerShape(3.dp))
                                                .border(BorderStroke(1.dp, Color(0xFFC6A34E)), RoundedCornerShape(3.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("O", color = Color(0xFF174A91), fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                                        }
                                        Spacer(Modifier.width(9.dp))
                                        Text(
                                            message.content, Modifier.weight(1f).padding(top = 2.dp),
                                            color = Color(0xFF202B36), fontSize = 14.sp, lineHeight = 22.sp
                                        )
                                    }
                                }
                            }
                            if (busy) item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(15.dp), color = Color(0xFF1768C4), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text("ORACLE is working…", color = Color(0xFF58718B), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(error!!, Modifier.fillMaxWidth().background(Color(0xFFFFE4E1)).padding(7.dp),
                    color = Color(0xFF9E2921), fontSize = 11.sp)
                if (failedPrompt != null && prompt != failedPrompt) {
                    TextButton(onClick = { onPrompt(failedPrompt.orEmpty()) }, enabled = !busy) {
                        Text("Restore prompt")
                    }
                }
            }

            // XP taskbar-like composer: recessed white editor, raised classic buttons.
            Column(
                Modifier.fillMaxWidth().padding(top = 5.dp)
                    .background(Color(0xFFECE9D8))
                    .border(BorderStroke(1.dp, Color.White))
                    .padding(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 76.dp)
                        .background(Color.White)
                        .border(BorderStroke(1.dp, Color(0xFF7F9DB9)))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = prompt, onValueChange = onPrompt,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message…", color = Color(0xFF8797A6), fontSize = 14.sp) },
                        minLines = 1, maxLines = 5,
                        shape = RoundedCornerShape(0.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color(0xFF172B40), unfocusedTextColor = Color(0xFF172B40),
                            cursorColor = Color(0xFF0756B8)
                        )
                    )
                    Spacer(Modifier.width(5.dp))
                    if (busy) {
                        XPButton(
                            "Cancel",
                            Modifier.width(65.dp).height(39.dp),
                            onClick = { requestJob?.cancel() }
                        )
                    } else {
                        XPButton(
                            "Send",
                            Modifier.width(65.dp).height(39.dp),
                            enabled = prompt.isNotBlank(),
                            primary = true,
                            onClick = {
                                val submitted = prompt.trim()
                                if (submitted.isBlank()) return@XPButton
                                val selected = activeSessionId
                                val turns = messages.map { AgentTurn(it.role, it.content) }
                                val submission = AgentConversationPolicy.submissionHistory(
                                    turns = turns,
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
                                onPrompt("")
                                error = null
                                status = "SENDING TO GEMINI"
                                busy = true
                                requestJob = scope.launch {
                                    var userMessagePersisted = retry
                                    try {
                                        val sid = selected
                                            ?: repository.createSession(model).also { activeSessionId = it }
                                        val key = try {
                                            withContext(Dispatchers.IO) { keyStore.read().orEmpty() }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            error = "The stored Gemini key could not be decrypted. Remove it in Settings and reconnect."
                                            failedPrompt = submitted
                                            failedPromptPersisted = userMessagePersisted
                                            status = "CREDENTIAL ERROR"
                                            onPrompt(submitted)
                                            return@launch
                                        }
                                        if (key.isBlank()) {
                                            error = "Add your Gemini API key in Settings to start chatting."
                                            failedPrompt = submitted
                                            failedPromptPersisted = userMessagePersisted
                                            status = "GEMINI KEY REQUIRED"
                                            onPrompt(submitted)
                                            return@launch
                                        }
                                        if (!retry) {
                                            repository.appendMessage(sid, "user", submitted, model)
                                            userMessagePersisted = true
                                            failedPromptPersisted = true
                                        }
                                        when (val result = api.generate(
                                            key,
                                            submitted,
                                            AgentConversationPolicy.context(history),
                                            model
                                        )) {
                                            is studio.oracle.data.GeminiReplyResult.Success -> {
                                                repository.appendMessage(sid, "assistant", result.text, result.model)
                                                failedPrompt = null
                                                failedPromptPersisted = false
                                                status = "RESPONSE SAVED"
                                            }
                                            is studio.oracle.data.GeminiReplyResult.Failed -> {
                                                error = result.message
                                                failedPrompt = submitted
                                                failedPromptPersisted = userMessagePersisted
                                                status = "REQUEST FAILED"
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        failedPrompt = submitted
                                        failedPromptPersisted = userMessagePersisted
                                        onPrompt(submitted)
                                        status = "REQUEST CANCELLED"
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "Something went wrong. Check your connection and try again."
                                        failedPrompt = submitted
                                        failedPromptPersisted = userMessagePersisted
                                        status = "REQUEST FAILED"
                                    } finally {
                                        busy = false
                                        requestJob = null
                                    }
                                }
                            }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    XPButton(if (toolsExpanded) "Tools  ▲" else "Tools  ▼", onClick = { toolsExpanded = !toolsExpanded })
                    Spacer(Modifier.weight(1f))
                    Text(
                        "CHAT SENDS PROMPT + LAST 12 MESSAGES TO GOOGLE GEMINI",
                        color = Color(0xFF708398),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedVisibility(
                    visible = toolsExpanded,
                    enter = fadeIn() + slideInVertically { -it / 4 },
                    exit = fadeOut() + slideOutVertically { -it / 4 }
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 7.dp)
                            .background(Color(0xFFF7F7F2))
                            .border(BorderStroke(1.dp, Color(0xFFB8C7D5)))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, tint = Color(0xFF245B91), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Model", color = Color(0xFF354D64), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Box {
                                XPButton(menuModels.firstOrNull { it.name == model }?.displayName ?: model, onClick = { modelMenuExpanded = true })
                                DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                    menuModels.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(candidate.displayName, fontSize = 13.sp) },
                                            onClick = {
                                                modelMenuExpanded = false
                                                scope.launch {
                                                    modelPreferences.setSelectedModel(candidate.name)
                                                    activeSessionId?.let { repository.updateModel(it, candidate.name) }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            XPButton(if (syncingModels) "…" else "Refresh", onClick = { refreshModels() }, enabled = !syncingModels)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XPButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val face = if (primary) Color(0xFFE0EDFC) else Color(0xFFF4F2E9)
    val edge = if (primary) Color(0xFF7A9CC5) else Color(0xFF9B9A91)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 31.dp),
        shape = RoundedCornerShape(3.dp),
        color = face,
        border = BorderStroke(1.dp, edge),
        shadowElevation = if (enabled) 1.dp else 0.dp
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 48.dp).padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (enabled) Color(0xFF24394D) else Color(0xFF9AA3AC),
                fontSize = 11.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun XPWindowButton(
    label: String,
    onClick: () -> Unit,
    close: Boolean = false,
    description: String? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 25.dp, height = 22.dp).then(
            if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        ),
        shape = RoundedCornerShape(4.dp),
        color = if (close) Color(0xFFE36A5C) else Color(0xFF5A9CE8),
        border = BorderStroke(1.dp, if (close) Color(0xFFFFB5A9) else Color(0xFFB9D8FA)),
        shadowElevation = 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = if (label == "×") 17.sp else 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun XPMenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        color = Color(0xFF263B50), fontSize = 11.sp
    )
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SecureKeyStore(context) }
    val api = remember { GeminiApi() }
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf(store.hasKey()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (stored) "Encrypted Gemini key found. Verify to test the connection." else "No Gemini key connected.") }
    var success by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("MODEL CONNECTION", color = Accent, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Text("Bring your own intelligence.", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Connect Gemini directly. Your key is encrypted with Android Keystore and stays on this device.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        Surface(color = Panel, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("GEMINI API KEY", color = Paper, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (stored) Text("ENCRYPTED", color = Accent, fontSize = 8.sp, letterSpacing = 1.sp)
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(if (stored) "Key saved · enter a new key to replace" else "Paste your Gemini API key", color = Muted, fontSize = 11.sp) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (showKey) "Hide API key" else "Show API key",
                                tint = Muted
                            )
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Verifying key with Google Gemini…"
                            val enteredKey = keyInput.trim()
                            val candidate = if (enteredKey.isNotBlank()) {
                                enteredKey
                            } else {
                                try {
                                    withContext(Dispatchers.IO) { store.read().orEmpty() }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    status = "Stored key could not be decrypted. Remove it and reconnect."
                                    success = false
                                    busy = false
                                    return@launch
                                }
                            }
                            when (val result = api.validateKey(candidate)) {
                                is GeminiConnectionResult.Connected -> {
                                    if (enteredKey.isNotBlank()) {
                                        try {
                                            withContext(Dispatchers.IO) { store.save(candidate) }
                                            stored = true
                                            keyInput = ""
                                            status = result.message
                                            success = true
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            status = "Verified, but secure storage failed. The previous saved key was not replaced."
                                            success = false
                                        }
                                    } else {
                                        status = result.message
                                        success = true
                                    }
                                }
                                is GeminiConnectionResult.Failed -> {
                                    status = result.message
                                    success = false
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && (keyInput.isNotBlank() || stored),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink, disabledContainerColor = Raised)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink)
                    else Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "WORKING…" else "VERIFY & SECURE KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Info, null,
                        tint = if (success) Accent else Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = if (success) Accent else Muted, fontSize = 10.sp)
                }
                if (stored) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Removing encrypted Gemini credential…"
                                try {
                                    withContext(Dispatchers.IO) { store.clear() }
                                    stored = false
                                    keyInput = ""
                                    success = false
                                    status = "Stored key and its Keystore alias were removed from this device."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    success = false
                                    status = "Could not remove the stored key. Try again."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("REMOVE STORED KEY", fontSize = 9.sp)
                    }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PRIVACY BY DESIGN", color = Accent, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text("• API key encrypted using AES-GCM; encryption key held by Android Keystore.", color = Muted, fontSize = 10.sp)
                Text("• Key is sent only to Google's Gemini API over HTTPS when you invoke a model action.", color = Muted, fontSize = 10.sp)
                Text("• No key is embedded in the APK, source code, analytics, or logs.", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("ORACLE / MODEL GATEWAY", color = Muted, fontSize = 8.sp, letterSpacing = 1.3.sp)
    }
}

