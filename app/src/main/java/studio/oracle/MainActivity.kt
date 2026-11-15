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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OracleCanvas = Color(0xFF101317)
private val OraclePanel = Color(0xFF171C22)
private val OracleRaised = Color(0xFF202730)
private val OracleLine = Color(0xFF2B343F)
private val OracleText = Color(0xFFE9E7E1)
private val OracleMuted = Color(0xFF929BA5)
private val OracleAccent = Color(0xFFC85A42)
private val OracleAccentSoft = Color(0xFF33231E)
private val Ink = OracleText
private val Panel = OraclePanel
private val Raised = OracleRaised
private val Line = OracleLine
private val Paper = OracleText
private val Muted = OracleMuted
private val Accent = OracleAccent
private val Violet = Color(0xFFB49AF4)
private val Glass = OracleRaised

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OracleApp() }
    }
}

private enum class OracleSection { AGENT, LIBRARY, SETTINGS }

@Composable
private fun OracleApp() {
    var prompt by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(OracleSection.AGENT) }

    BackHandler(enabled = section != OracleSection.AGENT) {
        section = OracleSection.AGENT
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = OracleCanvas, surface = OraclePanel, surfaceVariant = OracleRaised,
            primary = OracleAccent, secondary = Violet, onBackground = OracleText,
            onSurface = OracleText, onSurfaceVariant = OracleMuted
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = OracleCanvas) {
            when (section) {
                OracleSection.AGENT -> AgentScreen(
                    prompt = prompt,
                    onPrompt = { prompt = it },
                    onSettings = { section = OracleSection.SETTINGS },
                    onLibrary = { section = OracleSection.LIBRARY }
                )

                OracleSection.LIBRARY -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).background(OracleCanvas).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkbenchIconButton(Icons.Default.ArrowBack, "Back to ORACLE chat") { section = OracleSection.AGENT }
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("ORACLE", color = OracleText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                            Text("LIBRARY", color = OracleAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        }
                    }
                    LibraryScreen()
                }

                OracleSection.SETTINGS -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).background(OracleCanvas).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkbenchIconButton(Icons.Default.ArrowBack, "Back to ORACLE chat") { section = OracleSection.AGENT }
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("ORACLE", color = OracleText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                            Text("SETTINGS", color = OracleAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        }
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
    onLibrary: () -> Unit
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

    fun createNewThread(closeDrawer: Boolean = false) {
        uiScope.launch {
            activeSessionId = repository.createSession(model)
            error = null
            failedPrompt = null
            failedPromptPersisted = false
            status = "READY / LOCAL SESSION"
            if (closeDrawer) drawer.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color(0x66000000),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight().width(304.dp),
                drawerContainerColor = OraclePanel,
                drawerShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
            ) {
                Column(Modifier.fillMaxSize().background(OraclePanel).padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(OracleRaised, RoundedCornerShape(14.dp))
                            .padding(horizontal = 13.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).background(OracleAccent, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Text("O", color = OracleCanvas, fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("ORACLE", color = OracleText, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Text("INTELLIGENCE WORKSPACE", color = OracleMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        WorkbenchWindowButton("×", onClick = { uiScope.launch { drawer.close() } }, close = true, description = "Close chat history")
                    }
                    Spacer(Modifier.height(9.dp))
                    WorkbenchButton("＋   New conversation", Modifier.fillMaxWidth(), onClick = {
                        createNewThread(closeDrawer = true)
                    }, primary = true)
                    Spacer(Modifier.height(13.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENT THREADS", color = OracleMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${sessions.size}", color = OracleMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    HorizontalDivider(color = OracleLine)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            val selected = session.id == activeSessionId
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (selected) OracleAccentSoft else Color.Transparent, RoundedCornerShape(10.dp))
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
                                    tint = if (selected) OracleAccent else OracleMuted,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    session.title.ifBlank { "New conversation" },
                                    color = if (selected) OracleText else OracleMuted, fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    WorkbenchButton("⚙   Settings", Modifier.fillMaxWidth(), onClick = {
                        onSettings()
                        uiScope.launch { drawer.close() }
                    })
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(OracleCanvas).oracleGrain()) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).background(OracleCanvas).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ORACLE", color = OracleText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.4.sp)
                    Text("INTELLIGENCE / LOCAL", color = OracleMuted, fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.1.sp)
                }
                WorkbenchButton("＋ NEW", Modifier.width(76.dp), onClick = { createNewThread() }, primary = true, sharp = true)
                Spacer(Modifier.width(5.dp))
                WorkbenchIconButton(Icons.Default.Menu, "Open conversations", onClick = { uiScope.launch { drawer.open() } })
                Spacer(Modifier.width(5.dp))
                WorkbenchIconButton(Icons.Default.FolderOpen, "Open document library", onClick = onLibrary)
                Spacer(Modifier.width(5.dp))
                WorkbenchIconButton(Icons.Default.Settings, "Open settings", onClick = onSettings)
            }

            // Main conversation surface.
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .background(OracleCanvas)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).background(OracleCanvas).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Forum, null, tint = OracleAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        sessions.firstOrNull { it.id == activeSessionId }?.title?.ifBlank { "Conversation" } ?: "Conversation",
                        color = OracleText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    Text("●", color = Color(0xFF70B78A), fontSize = 8.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("LOCAL SESSION", color = OracleMuted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
                HorizontalDivider(color = OracleLine)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(start = 30.dp, end = 54.dp, top = 24.dp, bottom = 22.dp),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text("FIELD NOTES  /  001", color = OracleAccent, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.8.sp)
                            Spacer(Modifier.height(13.dp))
                            Text(
                                "Find the signal.\\nIgnore the noise.",
                                color = OracleText, fontSize = 35.sp, lineHeight = 40.sp,
                                fontWeight = FontWeight.Normal, fontFamily = FontFamily.Serif,
                                letterSpacing = (-1.1).sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Bring the question you cannot leave alone.",
                                color = OracleMuted, fontSize = 11.sp, lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 22.dp),
                            verticalArrangement = Arrangement.spacedBy(19.dp),
                            contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                if (message.role == "user") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Surface(
                                            color = OracleRaised,
                                            shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = 17.dp, bottomEnd = 5.dp),
                                            border = BorderStroke(1.dp, OracleLine),
                                            modifier = Modifier.widthIn(max = 310.dp)
                                        ) {
                                            Text(
                                                message.content,
                                                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                color = OracleText, fontSize = 14.sp, lineHeight = 22.sp
                                            )
                                        }
                                    }
                                } else {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Box(
                                            Modifier.size(28.dp).background(OracleAccentSoft, RoundedCornerShape(9.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("O", color = OracleAccent, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif)
                                        }
                                        Spacer(Modifier.width(9.dp))
                                        Text(
                                            message.content, Modifier.weight(1f).padding(top = 2.dp),
                                            color = OracleText, fontSize = 14.sp, lineHeight = 23.sp
                                        )
                                    }
                                }
                            }
                            if (busy) item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(15.dp), color = OracleAccent, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text("ORACLE is thinking…", color = OracleMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(error!!, Modifier.fillMaxWidth().background(Color(0xFF3A2423), RoundedCornerShape(10.dp)).padding(11.dp),
                    color = Color(0xFFFFB8AE), fontSize = 11.sp)
                if (failedPrompt != null && prompt != failedPrompt) {
                    TextButton(onClick = { onPrompt(failedPrompt.orEmpty()) }, enabled = !busy) {
                        Text("Restore prompt")
                    }
                }
            }

            // Composer and progressive-disclosure controls stay together at the bottom edge.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)
                    .background(OraclePanel, RoundedCornerShape(2.dp))
                    .border(BorderStroke(1.dp, OracleLine), RoundedCornerShape(2.dp))
                    .drawBehind {
                        val inset = 1.dp.toPx()
                        drawRect(Color(0xFF090C0F).copy(alpha = .34f), Offset.Zero, Size(size.width, inset))
                        drawRect(Color(0xFF090C0F).copy(alpha = .24f), Offset.Zero, Size(inset, size.height))
                        drawRect(Color.White.copy(alpha = .035f), Offset(0f, size.height - inset), Size(size.width, inset))
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 66.dp)
                        .background(OraclePanel, RoundedCornerShape(2.dp))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = prompt, onValueChange = onPrompt,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Write the first line…", color = OracleMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = OracleText, lineHeight = 19.sp),
                        minLines = 1, maxLines = 5,
                        shape = RoundedCornerShape(2.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = OracleText, unfocusedTextColor = OracleText,
                            cursorColor = OracleAccent
                        )
                    )
                    Spacer(Modifier.width(5.dp))
                    if (busy) {
                        WorkbenchButton(
                            "Cancel",
                            Modifier.width(70.dp).height(42.dp),
                            onClick = { requestJob?.cancel() },
                            sharp = true
                        )
                    } else {
                        WorkbenchButton(
                            "Send",
                            Modifier.width(70.dp).height(42.dp),
                            enabled = prompt.isNotBlank(),
                            primary = true,
                            sharp = true,
                            onClick = {
                                val submitted = prompt.trim()
                                if (submitted.isBlank()) return@WorkbenchButton
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
                    WorkbenchButton(if (toolsExpanded) "⌃  Controls" else "＋  Model & tools", onClick = { toolsExpanded = !toolsExpanded })
                    Spacer(Modifier.weight(1f))
                    Text(
                        "PROMPT + LAST 12 MESSAGES SHARED WITH GEMINI",
                        color = OracleMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
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
                            .background(OracleRaised, RoundedCornerShape(2.dp))
                            .border(BorderStroke(1.dp, OracleLine), RoundedCornerShape(2.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, tint = OracleAccent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("MODEL", color = OracleMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Spacer(Modifier.weight(1f))
                            Box {
                                WorkbenchButton(menuModels.firstOrNull { it.name == model }?.displayName ?: model, onClick = { modelMenuExpanded = true })
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
                            WorkbenchButton(if (syncingModels) "…" else "Refresh", onClick = { refreshModels() }, enabled = !syncingModels)
                        }
                    }
                }
            }
        }
    }
}


private fun Modifier.oracleGrain(): Modifier = drawWithContent {
    drawContent()
    val cell = 5.dp.toPx()
    val columns = (size.width / cell).toInt()
    val rows = (size.height / cell).toInt()
    val dot = .55.dp.toPx()
    for (y in 0 until rows step 2) {
        for (x in 0 until columns step 2) {
            val hash = (x * 73856093) xor (y * 19349663)
            if ((hash and 15) == 0) {
                drawRect(
                    color = Color.White.copy(alpha = .022f),
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(dot, dot)
                )
            }
        }
    }
}

@Composable
private fun WorkbenchIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(34.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(3.dp),
        color = OraclePanel,
        border = BorderStroke(1.dp, OracleLine)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = OracleText, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun WorkbenchButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    sharp: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val face = if (primary && hovered) OracleCanvas else if (primary) OracleAccent else OracleRaised
    val edge = if (primary) OracleAccent else OracleLine
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = 31.dp).hoverable(interactionSource),
        shape = if (sharp) RoundedCornerShape(2.dp) else RoundedCornerShape(10.dp),
        color = face,
        border = BorderStroke(1.dp, edge),
        shadowElevation = 0.dp
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 48.dp).padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (!enabled) OracleMuted else if (primary && hovered) OracleAccent else if (primary) OracleCanvas else OracleText,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = .2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WorkbenchWindowButton(
    label: String,
    onClick: () -> Unit,
    close: Boolean = false,
    description: String? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 36.dp, height = 36.dp).then(
            if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        ),
        shape = RoundedCornerShape(11.dp),
        color = if (close) OracleAccentSoft else OraclePanel,
        border = BorderStroke(1.dp, if (close) OracleAccent.copy(alpha = .35f) else OracleLine),
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = if (label == "×") 17.sp else 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun WorkbenchMenuItem(label: String, onClick: () -> Unit) {
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

