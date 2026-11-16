package studio.oracle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import studio.oracle.data.WorkspacePreferences
import studio.oracle.data.WorkspacePolicy
import studio.oracle.data.WorkspaceRepository
import studio.oracle.data.local.OracleDatabase
import studio.oracle.ui.HandcraftedLogo
import studio.oracle.ui.OracleWhaleMark
import studio.oracle.ui.LibraryScreen
import studio.oracle.ui.AudioMemoryScreen
import studio.oracle.ui.OracleGlyph
import studio.oracle.ui.OracleIconButton
import studio.oracle.ui.OracleScreen
import studio.oracle.ui.SettingsScreen
import studio.oracle.ui.WorkflowScreen
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleSans
import studio.oracle.ui.theme.OracleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OracleTheme {
                OracleRoot()
            }
        }
    }
}

private enum class OracleDestination {
    CHAT,
    LIBRARY,
    WORKFLOWS,
    AUDIO,
    SETTINGS
}

@Composable
private fun OracleRoot() {
    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1050)
        splashVisible = false
    }
    AnimatedContent(
        targetState = splashVisible,
        label = "oracle-startup",
        transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(260)) }
    ) { showingSplash ->
        if (showingSplash) OracleStartupSplash() else OracleWorkspaceRoot()
    }
}

@Composable
private fun OracleStartupSplash() {
    Box(
        Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            OracleWhaleMark(
                Modifier.size(width = 39.dp, height = 31.dp),
                Color(0xFF4F78E8)
            )
            Text(
                "Hi. What can I do for you?",
                color = Color(0xFF171717),
                fontFamily = OracleSans,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun OracleWorkspaceRoot() {
    val context = LocalContext.current
    val database = remember(context) { OracleDatabase.get(context) }
    val workspaceRepository = remember(database) { WorkspaceRepository(database.workspaceDao()) }
    val workspacePreferences = remember(context) { WorkspacePreferences(context) }
    val scope = rememberCoroutineScope()
    val workspaces by workspaceRepository.workspaces.collectAsState(initial = emptyList())
    val savedWorkspaceId by workspacePreferences.activeWorkspaceId.collectAsState(initial = null)
    var activeWorkspaceId by remember { mutableStateOf(WorkspacePolicy.DEFAULT_WORKSPACE_ID) }
    var workspaceSelectionRestored by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(OracleDestination.CHAT) }

    LaunchedEffect(Unit) {
        workspaceRepository.ensureDefaultWorkspace()
    }

    LaunchedEffect(workspaces, savedWorkspaceId) {
        if (workspaces.isNotEmpty() &&
            (!workspaceSelectionRestored || workspaces.none { it.id == activeWorkspaceId })
        ) {
            val resolved = savedWorkspaceId?.takeIf { id -> workspaces.any { it.id == id } }
                ?: workspaces.first().id
            activeWorkspaceId = resolved
            workspaceSelectionRestored = true
            if (savedWorkspaceId != resolved) {
                workspacePreferences.setActiveWorkspaceId(resolved)
            }
        }
    }

    fun selectWorkspace(id: String) {
        activeWorkspaceId = id
        scope.launch { workspacePreferences.setActiveWorkspaceId(id) }
    }

    BackHandler(enabled = destination != OracleDestination.CHAT) {
        destination = OracleDestination.CHAT
    }

    AnimatedContent(
        targetState = destination,
        label = "oracle-destination",
        transitionSpec = {
            (fadeIn(tween(190)) + slideInHorizontally(tween(240)) { it / 22 }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 22 })
        }
    ) { target ->
        when (target) {
        OracleDestination.CHAT -> OracleScreen(
            onOpenLibrary = { destination = OracleDestination.LIBRARY },
            onOpenSettings = { destination = OracleDestination.SETTINGS },
            onOpenWorkflows = { destination = OracleDestination.WORKFLOWS },
            onOpenAudio = { destination = OracleDestination.AUDIO },
            workspaceId = activeWorkspaceId,
            workspaces = workspaces,
            onWorkspaceChange = ::selectWorkspace,
            onCreateWorkspace = { name -> workspaceRepository.create(name) }
        )

        OracleDestination.LIBRARY -> OracleSection(
            title = "Library",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            LibraryScreen(workspaceId = activeWorkspaceId)
        }

        OracleDestination.WORKFLOWS -> OracleSection(
            title = "Workflow Studio",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            WorkflowScreen(workspaceId = activeWorkspaceId)
        }

        OracleDestination.AUDIO -> OracleSection(
            title = "Audio Memory",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            AudioMemoryScreen(workspaceId = activeWorkspaceId)
        }

        OracleDestination.SETTINGS -> OracleSection(
            title = "Settings",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            SettingsScreen()
        }
        }
    }
}
@Composable
private fun OracleSection(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalOraclePalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OracleIconButton(
                glyph = OracleGlyph.Back,
                description = "Back to chat",
                onClick = onBack
            )
            Spacer(Modifier.width(10.dp))
            HandcraftedLogo(Modifier.size(25.dp), palette.accent)
            Spacer(Modifier.width(9.dp))
            Text(
                title,
                color = palette.text,
                fontFamily = OracleSans,
                fontSize = 14.sp
            )
        }
        content()
    }
}
