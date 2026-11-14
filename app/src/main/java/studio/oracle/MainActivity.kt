package studio.oracle

import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101113)
private val Panel = Color(0xFF17191C)
private val Raised = Color(0xFF202328)
private val Line = Color(0xFF2A2D32)
private val Paper = Color(0xFFE9E9E7)
private val Muted = Color(0xFF858A93)
private val Accent = Color(0xFFC6F36B)
private val Violet = Color(0xFFB5A4FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OracleApp() }
    }
}

@Composable
private fun OracleApp() {
    var active by remember { mutableStateOf("Overview") }
    var prompt by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, surfaceVariant = Raised,
        primary = Accent, secondary = Violet, onBackground = Paper,
        onSurface = Paper, onSurfaceVariant = Muted
    )) {
        Column(Modifier.fillMaxSize().background(Ink)) {
            TopBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(active) { active = it }
                VerticalDivider(color = Line, thickness = 1.dp)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    WorkspaceHeader(active)
                    when (active) {
                        "Agent" -> AgentScreen(prompt, { prompt = it })
                        "Library" -> ModuleScreen("DOCUMENT INTELLIGENCE", "Your files, indexed and ready.", Icons.Default.FolderOpen, listOf("Import PDF or document", "Ask across your library", "Source-linked answers", "Collections & knowledge graph"))
                        "Automations" -> ModuleScreen("AUTOMATION STUDIO", "Design workflows. Inspect every run.", Icons.Default.AccountTree, listOf("Visual workflow canvas", "Triggers and actions", "Run history", "Local-first execution"))
                        "Memory" -> ModuleScreen("AUDIO MEMORY", "Capture, transcribe, retrieve.", Icons.Default.GraphicEq, listOf("Record with explicit permission", "Transcription archive", "Decisions and action items", "Semantic search"))
                        "Settings" -> SettingsScreen()
                        else -> OverviewScreen { active = it }
                    }
                }
            }
            StatusBar()
        }
    }
}

@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth().height(54.dp).background(Panel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp).background(Accent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text("O", color = Ink, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("ORACLE", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp)
        Text("  STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Surface(color = Raised, shape = RoundedCornerShape(6.dp)) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(6.dp))
                Text("LOCAL WORKSPACE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Default.MoreHoriz, null, tint = Muted)
    }
}

@Composable
private fun NavigationRail(active: String, onSelect: (String) -> Unit) {
    val items = listOf(
        "Overview" to Icons.Default.Dashboard, "Agent" to Icons.Default.AutoAwesome,
        "Library" to Icons.Default.FolderOpen, "Automations" to Icons.Default.AccountTree,
        "Memory" to Icons.Default.GraphicEq, "Settings" to Icons.Default.Settings
    )
    Column(Modifier.width(62.dp).fillMaxHeight().background(Panel).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.forEach { (name, icon) ->
            val selected = active == name
            Column(Modifier.width(48.dp).height(48.dp)
                .background(if (selected) Raised else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable { onSelect(name) }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(icon, name, tint = if (selected) Accent else Muted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("V.0.1", color = Muted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun WorkspaceHeader(active: String) {
    Row(Modifier.fillMaxWidth().height(43.dp).background(Ink).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("WORKSPACE", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text("  /  ", color = Line, fontSize = 11.sp)
        Text(active.uppercase(), color = Paper, fontSize = 9.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.Tune, null, tint = Muted, modifier = Modifier.size(17.dp))
    }
    HorizontalDivider(color = Line, thickness = 1.dp)
}

@Composable
private fun OverviewScreen(onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item {
            Text("INTELLIGENCE / 001", color = Accent, fontSize = 9.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Text("Good to have you\nhere.", color = Paper, fontSize = 31.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("One workspace. Every thought, file, workflow and idea.", color = Muted, fontSize = 12.sp)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Line)) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("START WITH ORACLE", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text("What are we working on?", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(7.dp))
                    Text("Ask, analyze, organize, or build a workflow.", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { onSelect("Agent") }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                        Text("Open agent workspace", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(7.dp))
                        Icon(Icons.Default.ArrowOutward, null, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        item { Text("YOUR ENGINES", color = Muted, fontSize = 9.sp, letterSpacing = 1.6.sp) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                EngineCard("01", "Agent", "Gemini-powered operator", Icons.Default.AutoAwesome, Accent) { onSelect("Agent") }
                EngineCard("02", "Library", "Documents & knowledge", Icons.Default.FolderOpen, Violet) { onSelect("Library") }
                EngineCard("03", "Automations", "Visual workflows & runs", Icons.Default.AccountTree, Color(0xFF78C9E8)) { onSelect("Automations") }
                EngineCard("04", "Memory", "Audio capture & recall", Icons.Default.GraphicEq, Color(0xFFE7A56E)) { onSelect("Memory") }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = Muted, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connect your intelligence layer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("Add a Gemini API key in Settings to activate AI.", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onSelect("Settings") }) { Text("SET UP", color = Accent, fontSize = 9.sp) }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(number: String, title: String, detail: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, color = Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(34.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AgentScreen(prompt: String, onPrompt: (String) -> Unit) {
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

    Column(Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AGENT CONSOLE", color = Accent, fontSize = 9.sp, letterSpacing = 1.7.sp)
                Text("Think beyond the prompt.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Gemini operator · explicit user-directed requests", color = Muted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { modelMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) {
                            Text(model, color = Accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ExpandMore, null, tint = Accent, modifier = Modifier.size(15.dp))
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            menuModels.forEach { candidate ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(candidate.displayName, fontSize = 12.sp)
                                            Text(candidate.name, color = Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    },
                                    onClick = {
                                        modelMenuExpanded = false
                                        scope.launch {
                                            modelPreferences.setSelectedModel(candidate.name)
                                            activeSessionId?.let { repository.updateModel(it, candidate.name) }
                                            status = "MODEL SELECTED / " + candidate.name
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { refreshModels() }, enabled = !syncingModels, modifier = Modifier.size(34.dp)) {
                        if (syncingModels) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "Refresh available Gemini models", tint = Muted, modifier = Modifier.size(17.dp))
                        }
                    }
                }
                Text("MODEL GATEWAY", color = Muted, fontSize = 7.sp, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == activeSessionId
                    Surface(
                        onClick = { if (!busy) activeSessionId = session.id },
                        color = if (selected) Raised else Panel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .55f) else Line)
                    ) {
                        Text(
                            session.title,
                            modifier = Modifier.widthIn(max = 150.dp).padding(horizontal = 9.dp, vertical = 7.dp),
                            color = if (selected) Paper else Muted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        val id = repository.createSession(model)
                        activeSessionId = id
                        error = null
                        failedPrompt = null
                        status = "NEW LOCAL THREAD"
                    }
                },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(14.dp))
                Text("NEW", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Plan a project", "Explain a document", "Break down a goal").forEach { suggestion ->
                Surface(
                    onClick = { onPrompt(suggestion) },
                    color = Panel,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Text(suggestion, color = Muted, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            color = Panel,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Line)
        ) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(15.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(48.dp).background(Accent.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(13.dp))
                    Text("ORACLE IS READY", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Give your first instruction. ORACLE will reason through it and return a practical plan.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "Submitting a prompt sends it plus the latest 12 messages in this thread to Gemini.",
                        color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(6.dp).background(
                                        if (message.role == "assistant") Accent else Violet,
                                        RoundedCornerShape(50)
                                    )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (message.role == "assistant") "ORACLE" else "YOU",
                                    color = if (message.role == "assistant") Accent else Violet,
                                    fontSize = 8.sp,
                                    letterSpacing = 1.2.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                color = if (message.role == "assistant") Raised else Ink,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Line)
                            ) {
                                Text(
                                    message.content,
                                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                                    color = Paper,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                    if (busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(9.dp))
                            Text("ORACLE IS THINKING…", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, color = Color(0xFFFF8B83), fontSize = 10.sp)
            if (failedPrompt != null) {
                TextButton(onClick = { onPrompt(failedPrompt.orEmpty()) }, enabled = !busy) {
                    Text("RESTORE PROMPT TO RETRY", color = Accent, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = onPrompt,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask ORACLE anything…", color = Muted, fontSize = 12.sp) },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(9.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line)
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, modifier = Modifier.weight(1f), color = Muted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            if (busy) {
                TextButton(onClick = { requestJob?.cancel() }) {
                    Text("CANCEL", color = Color(0xFFFF8B83), fontSize = 9.sp)
                }
            } else {
                TextButton(
                    onClick = {
                        val submitted = prompt.trim()
                        val selectedSession = activeSessionId
                        val existingTurns = messages.map { AgentTurn(it.role, it.content) }
                        val retrying = failedPrompt == submitted &&
                            existingTurns.lastOrNull()?.let { it.role == "user" && it.content == submitted } == true
                        val history = if (retrying) existingTurns.dropLast(1) else existingTurns
                        onPrompt("")
                        error = null
                        busy = true
                        status = "SENDING TO GEMINI"
                        requestJob = scope.launch {
                            try {
                                val sessionId = selectedSession ?: repository.createSession(model).also {
                                    activeSessionId = it
                                }
                                val key = keyStore.read().orEmpty()
                                if (key.isBlank()) {
                                    error = "Connect a Gemini API key in Settings before running ORACLE."
                                    failedPrompt = submitted
                                    onPrompt(submitted)
                                    status = "KEY REQUIRED"
                                    return@launch
                                }
                                if (!retrying) repository.appendMessage(sessionId, "user", submitted, model)
                                when (val result = api.generate(
                                    key,
                                    submitted,
                                    AgentConversationPolicy.context(history),
                                    model
                                )) {
                                    is studio.oracle.data.GeminiReplyResult.Success -> {
                                        repository.appendMessage(sessionId, "assistant", result.text, result.model)
                                        failedPrompt = null
                                        status = "COMPLETED / " + result.model.uppercase()
                                    }
                                    is studio.oracle.data.GeminiReplyResult.Failed -> {
                                        error = result.message
                                        failedPrompt = submitted
                                        onPrompt(submitted)
                                        status = "REQUEST FAILED"
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                error = "Request cancelled. Your local thread and submitted prompt were retained."
                                failedPrompt = submitted
                                onPrompt(submitted)
                                status = "REQUEST CANCELLED"
                                throw cancelled
                            } catch (_: Exception) {
                                error = "The request could not be completed. Your local prompt was retained."
                                failedPrompt = submitted
                                onPrompt(submitted)
                                status = "REQUEST FAILED"
                            } finally {
                                busy = false
                                requestJob = null
                            }
                        }
                    },
                    enabled = !busy && prompt.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Ink,
                        disabledContainerColor = Raised
                    )
                ) {
                    Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("RUN", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
            TextButton(
                onClick = {
                    activeSessionId?.let { id ->
                        scope.launch {
                            repository.clearMessages(id)
                            error = null
                            failedPrompt = null
                            status = "THREAD CLEARED"
                        }
                    }
                },
                enabled = !busy && activeSessionId != null
            ) {
                Text("CLEAR", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ModuleScreen(title: String, subtitle: String, icon: ImageVector, features: List<String>) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(48.dp).background(Raised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(23.dp))
        }
        Text(title, color = Accent, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Text(subtitle, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        features.forEachIndexed { i, feature ->
            Surface(color = Panel, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Line)) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(i + 1), color = Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(12.dp))
                    Text(feature, color = Paper, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("MODULE IN ACTIVE DEVELOPMENT", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp)
    }
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
    var status by remember { mutableStateOf(if (stored) "Encrypted Gemini credential found on this device." else "No Gemini key connected.") }
    var success by remember { mutableStateOf(stored) }

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
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted)
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
                            val candidate = keyInput.trim().ifBlank { runCatching { store.read().orEmpty() }.getOrDefault("") }
                            when (val result = api.validateKey(candidate)) {
                                is GeminiConnectionResult.Connected -> {
                                    if (keyInput.isNotBlank()) {
                                        runCatching { store.save(candidate) }
                                            .onSuccess { stored = true; keyInput = "" }
                                            .onFailure { status = "Verified, but secure storage failed. Try again."; success = false }
                                    }
                                    if (keyInput.isBlank() || stored) { status = result.message; success = true }
                                }
                                is GeminiConnectionResult.Failed -> { status = result.message; success = false }
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
                    Text(if (busy) "VERIFYING…" else "VERIFY & SECURE KEY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Info, null,
                        tint = if (success) Accent else Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = if (success) Accent else Muted, fontSize = 10.sp)
                }
                if (stored) {
                    TextButton(onClick = {
                        store.clear()
                        stored = false
                        success = false
                        status = "Stored key removed from this device."
                    }) {
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

@Composable
private fun StatusBar() {
    HorizontalDivider(color = Line, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().height(27.dp).background(Panel).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Accent, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text("ORACLE CORE", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("LOCAL-FIRST  /  GEMINI ON DEMAND", color = Muted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}
