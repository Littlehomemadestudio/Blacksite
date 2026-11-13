package studio.oracle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
                        "Settings" -> ModuleScreen("SYSTEM SETTINGS", "Model, credentials, privacy and workspace.", Icons.Default.Settings, listOf("Gemini API connection", "Model configuration", "Encrypted local key storage", "Privacy & data controls"))
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("AGENT CONSOLE", color = Accent, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(8.dp))
        Text("Think beyond the prompt.", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Plan tasks, use tools, and keep every action inspectable.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ORACLE / READY", color = Accent, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text("Your workspace is ready for its first instruction.", color = Muted, fontSize = 12.sp)
                Text("Connect a Gemini API key in Settings to start an agent session.", color = Paper, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = prompt, onValueChange = onPrompt, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tell ORACLE what to do…", color = Muted) }, shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line))
        Spacer(Modifier.height(8.dp))
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink, disabledContainerColor = Raised),
            shape = RoundedCornerShape(8.dp)) {
            Text("RUN AGENT", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(16.dp))
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
private fun StatusBar() {
    HorizontalDivider(color = Line, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().height(27.dp).background(Panel).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Accent, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text("ORACLE CORE", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("GEMINI  /  NOT CONNECTED", color = Muted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}
