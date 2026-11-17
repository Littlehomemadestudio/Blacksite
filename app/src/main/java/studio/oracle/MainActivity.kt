package studio.oracle

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.viewinterop.AndroidView
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
        window.statusBarColor = android.graphics.Color.rgb(244, 245, 248)
        window.navigationBarColor = android.graphics.Color.rgb(244, 245, 248)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            OracleTheme {
                OracleRoot()
            }
        }
    }
}

private enum class OracleDestination {
    HOME, CHAT, LIBRARY, WORKFLOWS, AUDIO, SETTINGS
}

private class OracleWebBridge(private val navigate: (OracleDestination) -> Unit) {
    @JavascriptInterface
    fun open(destination: String) {
        val target = when (destination) {
            "assistant" -> OracleDestination.CHAT
            "library" -> OracleDestination.LIBRARY
            "workflows" -> OracleDestination.WORKFLOWS
            "audio" -> OracleDestination.AUDIO
            "settings" -> OracleDestination.SETTINGS
            "home" -> OracleDestination.HOME
            else -> null
        }
        target?.let(navigate)
    }
}

@Composable
private fun OracleRoot() {
    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(850)
        splashVisible = false
    }
    AnimatedContent(
        targetState = splashVisible,
        label = "oracle-startup",
        transitionSpec = { fadeIn(tween(360)) togetherWith fadeOut(tween(220)) }
    ) { showingSplash ->
        if (showingSplash) OracleStartupSplash() else OracleWorkspaceRoot()
    }
}

@Composable
private fun OracleStartupSplash() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFFF4F5F8)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            OracleWhaleMark(Modifier.size(width = 39.dp, height = 31.dp), Color(0xFF4778E8))
            Text(
                "A clearer space to think.",
                color = Color(0xFF20232B),
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
    var destination by remember { mutableStateOf(OracleDestination.HOME) }

    LaunchedEffect(Unit) { workspaceRepository.ensureDefaultWorkspace() }
    LaunchedEffect(workspaces, savedWorkspaceId) {
        if (workspaces.isNotEmpty() &&
            (!workspaceSelectionRestored || workspaces.none { it.id == activeWorkspaceId })
        ) {
            val resolved = savedWorkspaceId?.takeIf { id -> workspaces.any { it.id == id } }
                ?: workspaces.first().id
            activeWorkspaceId = resolved
            workspaceSelectionRestored = true
            if (savedWorkspaceId != resolved) workspacePreferences.setActiveWorkspaceId(resolved)
        }
    }

    fun selectWorkspace(id: String) {
        activeWorkspaceId = id
        scope.launch { workspacePreferences.setActiveWorkspaceId(id) }
    }

    BackHandler(enabled = destination != OracleDestination.HOME) {
        destination = OracleDestination.HOME
    }

    AnimatedContent(
        targetState = destination,
        label = "oracle-destination",
        transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 24 }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it / 24 })
        }
    ) { target ->
        when (target) {
            OracleDestination.HOME -> OracleHomeWebView { destination = it }
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
            OracleDestination.LIBRARY -> OracleSection("Library", { destination = OracleDestination.HOME }) {
                LibraryScreen(workspaceId = activeWorkspaceId)
            }
            OracleDestination.WORKFLOWS -> OracleSection("Workflow Studio", { destination = OracleDestination.HOME }) {
                WorkflowScreen(workspaceId = activeWorkspaceId)
            }
            OracleDestination.AUDIO -> OracleSection("Audio Memory", { destination = OracleDestination.HOME }) {
                AudioMemoryScreen(workspaceId = activeWorkspaceId)
            }
            OracleDestination.SETTINGS -> OracleSection("Settings", { destination = OracleDestination.HOME }) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun OracleHomeWebView(onNavigate: (OracleDestination) -> Unit) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(244, 245, 248))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            addJavascriptInterface(OracleWebBridge { destination ->
                post { onNavigate(destination) }
            }, "OracleNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            }
            loadUrl("file:///android_asset/index.html")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun OracleSection(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalOraclePalette.current
    Column(
        modifier = Modifier.fillMaxSize().background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OracleIconButton(OracleGlyph.Back, "Back to ORACLE home", onBack)
            Spacer(Modifier.width(10.dp))
            HandcraftedLogo(Modifier.size(25.dp), palette.accent)
            Spacer(Modifier.width(9.dp))
            Text(title, color = palette.text, fontFamily = OracleSans, fontSize = 14.sp)
        }
        content()
    }
}
