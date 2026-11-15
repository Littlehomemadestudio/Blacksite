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
import studio.oracle.ui.LibraryScreen
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
    val palette = LocalOraclePalette.current
    val motion = rememberInfiniteTransition(label = "startup-motion")
    val rotation by motion.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200), repeatMode = RepeatMode.Restart),
        label = "logo-rotation"
    )
    val pulse by motion.animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse),
        label = "logo-pulse"
    )
    Box(Modifier.fillMaxSize().background(palette.canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().border(1.dp, palette.accent.copy(alpha = 0.20f), CircleShape))
                Box(Modifier.fillMaxSize().padding(9.dp).rotate(rotation).border(1.dp, palette.accent.copy(alpha = 0.42f), CircleShape))
                HandcraftedLogo(Modifier.size(54.dp).scale(pulse), palette.accent)
            }
            Text("ORACLE", color = palette.text, fontFamily = OracleSans, fontSize = 22.sp, letterSpacing = 4.5.sp)
            Text("INTELLIGENCE WORKSPACE", color = palette.accent, fontFamily = studio.oracle.ui.theme.OracleMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.height(15.dp))
            Text("INITIALIZING WORKSPACE", color = palette.textSecondary, fontFamily = studio.oracle.ui.theme.OracleMono, fontSize = 8.sp, letterSpacing = 1.sp)
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
