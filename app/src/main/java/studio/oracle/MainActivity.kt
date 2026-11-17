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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Ink = Color(0xFF0A0A0C)
private val Panel = Color(0xFF101014)
private val Brass = Color(0xFFE2B15C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 10, 12)
        window.navigationBarColor = android.graphics.Color.rgb(10, 10, 12)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { OracleTheme(darkTheme = true) { OracleRoot() } }
    }
}

private enum class Destination { HOME, CHAT, LIBRARY, WORKFLOWS, AUDIO, SETTINGS }
private class OracleWebBridge(private val navigate: (Destination) -> Unit) {
    @JavascriptInterface fun open(destination: String) {
        val target = when (destination) {
            "assistant" -> Destination.CHAT
            "library" -> Destination.LIBRARY
            "workflows" -> Destination.WORKFLOWS
            "audio" -> Destination.AUDIO
            "settings" -> Destination.SETTINGS
            "home" -> Destination.HOME
            else -> null
        }
        target?.let(navigate)
    }
}

@Composable
private fun OracleRoot() {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1000); splash = false }
    AnimatedContent(splash, label = "startup", transitionSpec = {
        fadeIn(tween(420)) togetherWith fadeOut(tween(240))
    }) { if (it) StartupSplash() else WorkspaceRoot() }
}

@Composable
private fun StartupSplash() {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OracleWhaleMark(Modifier.size(width = 42.dp, height = 34.dp), Brass)
            Text("ORACLE", color = Color(0xFFE8E5DE), fontFamily = OracleSans, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 3.2.sp)
            Text("A PRIVATE INTELLIGENCE WORKSPACE", color = Color(0xFF77747D), fontSize = 8.sp, letterSpacing = 1.5.sp)
            Box(Modifier.width(38.dp).height(1.dp).background(Brass.copy(alpha = .65f)))
        }
    }
}

@Composable
private fun WorkspaceRoot() {
    val context = LocalContext.current
    val database = remember(context) { OracleDatabase.get(context) }
    val repository = remember(database) { WorkspaceRepository(database.workspaceDao()) }
    val preferences = remember(context) { WorkspacePreferences(context) }
    val scope = rememberCoroutineScope()
    val workspaces by repository.workspaces.collectAsState(initial = emptyList())
    val savedId by preferences.activeWorkspaceId.collectAsState(initial = null)
    var workspaceId by remember { mutableStateOf(WorkspacePolicy.DEFAULT_WORKSPACE_ID) }
    var restored by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(Destination.HOME) }
    LaunchedEffect(Unit) { repository.ensureDefaultWorkspace() }
    LaunchedEffect(workspaces, savedId) {
        if (workspaces.isNotEmpty() && (!restored || workspaces.none { it.id == workspaceId })) {
            val id = savedId?.takeIf { saved -> workspaces.any { it.id == saved } } ?: workspaces.first().id
            workspaceId = id; restored = true
            if (savedId != id) preferences.setActiveWorkspaceId(id)
        }
    }
    fun selectWorkspace(id: String) { workspaceId = id; scope.launch { preferences.setActiveWorkspaceId(id) } }
    BackHandler(destination != Destination.HOME) { destination = Destination.HOME }
    AnimatedContent(destination, label = "destination", transitionSpec = {
        (fadeIn(tween(170)) + slideInHorizontally(tween(200)) { it / 30 }) togetherWith
            (fadeOut(tween(120)) + slideOutHorizontally(tween(150)) { -it / 30 })
    }) { target ->
        when (target) {
            Destination.HOME -> HomeWebView { destination = it }
            Destination.CHAT -> OracleScreen(
                onOpenLibrary = { destination = Destination.LIBRARY },
                onOpenSettings = { destination = Destination.SETTINGS },
                onOpenWorkflows = { destination = Destination.WORKFLOWS },
                onOpenAudio = { destination = Destination.AUDIO },
                workspaceId = workspaceId, workspaces = workspaces,
                onWorkspaceChange = ::selectWorkspace,
                onCreateWorkspace = { name -> repository.create(name) }
            )
            Destination.LIBRARY -> SectionShell("Knowledge library", { destination = Destination.HOME }, { destination = it }) { LibraryScreen(workspaceId) }
            Destination.WORKFLOWS -> SectionShell("Workflow studio", { destination = Destination.HOME }, { destination = it }) { WorkflowScreen(workspaceId) }
            Destination.AUDIO -> SectionShell("Audio memory", { destination = Destination.HOME }, { destination = it }) { AudioMemoryScreen(workspaceId) }
            Destination.SETTINGS -> SectionShell("Settings", { destination = Destination.HOME }, { destination = it }) { SettingsScreen() }
        }
    }
}

@Composable
private fun HomeWebView(onNavigate: (Destination) -> Unit) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(10, 10, 12))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(OracleWebBridge { target -> post { onNavigate(target) } }, "OracleNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            }
            loadUrl("file:///android_asset/index.html")
        }
    }
    DisposableEffect(webView) {
        onDispose { (webView.parent as? android.view.ViewGroup)?.removeView(webView); webView.stopLoading(); webView.destroy() }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun SectionShell(title: String, onBack: () -> Unit, navigate: (Destination) -> Unit, content: @Composable () -> Unit) {
    val palette = LocalOraclePalette.current
    val currentDestination = when (title) {
        "Knowledge library" -> Destination.LIBRARY
        "Workflow studio" -> Destination.WORKFLOWS
        "Audio memory" -> Destination.AUDIO
        "Settings" -> Destination.SETTINGS
        else -> Destination.HOME
    }
    Column(Modifier.fillMaxSize().background(palette.canvas).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(49.dp).background(Panel).border(.5.dp, palette.outlineSoft), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(7.dp))
            OracleIconButton(OracleGlyph.Back, "Back to workspace", onBack, tint = palette.textSecondary, size = 35.dp)
            Spacer(Modifier.width(7.dp))
            HandcraftedLogo(Modifier.size(23.dp), palette.accent)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("ORACLE  /  WORKSPACE", color = palette.textTertiary, fontSize = 7.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 1.sp)
                Text(title, color = palette.text, fontFamily = OracleSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("LOCAL", color = palette.success, fontSize = 7.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = .8.sp)
            Spacer(Modifier.width(13.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Row(Modifier.fillMaxWidth().height(49.dp).background(Panel).border(.5.dp, palette.outlineSoft)
            .windowInsetsPadding(WindowInsets.navigationBars), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple(Destination.HOME, "⌂", "HOME"), Triple(Destination.CHAT, "✳", "AGENT"),
                Triple(Destination.LIBRARY, "▤", "FILES"), Triple(Destination.WORKFLOWS, "⑂", "FLOWS"),
                Triple(Destination.AUDIO, "◖", "AUDIO"), Triple(Destination.SETTINGS, "⚙", "SETTINGS")).forEach { (target, glyph, label) ->
                val active = target == currentDestination
                Column(Modifier.weight(1f).fillMaxHeight().clickable { if (target == Destination.HOME) onBack() else navigate(target) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(glyph, color = if (active) palette.accent else palette.textTertiary, fontSize = 15.sp)
                    Text(label, color = if (active) palette.accent else palette.textTertiary,
                        fontSize = 6.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = .5.sp)
                }
            }
        }
    }
}
