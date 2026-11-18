package studio.oracle.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.oracle.data.CrossMemoryStore
import studio.oracle.data.GeminiApi
import studio.oracle.data.GeminiConnectionResult
import studio.oracle.security.SecureKeyStore
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val palette = LocalOraclePalette.current
    val store = remember { SecureKeyStore(context) }
    val crossMemoryStore = remember(context) { CrossMemoryStore(context) }
    var crossMemoryEnabled by remember { mutableStateOf(crossMemoryStore.isEnabled()) }
    val api = remember { GeminiApi() }
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var keyLabel by remember { mutableStateOf("Primary") }
    var savedKeyLabels by remember { mutableStateOf(store.keyLabels()) }
    var activeKeyLabel by remember { mutableStateOf(store.activeKeyLabel()) }
    var showKey by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf(store.hasKey()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (stored) "Encrypted Gemini credential found."
            else "No Gemini credential connected."
        )
    }
    var success by remember { mutableStateOf(false) }
    var tavilyInput by remember { mutableStateOf("") }
    var showTavilyKey by remember { mutableStateOf(false) }
    var tavilyStored by remember { mutableStateOf(store.hasTavilyKey()) }
    var tavilyStatus by remember {
        mutableStateOf(if (tavilyStored) "Web search credential encrypted on this device." else "No independent web-search credential connected.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "MODEL CONNECTION",
                color = palette.accent,
                fontFamily = OracleMono,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp
            )
            Text(
                "Gemini API",
                color = palette.text,
                fontFamily = OracleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 25.sp,
                letterSpacing = (-0.6).sp
            )
            Text(
                "Your credential is encrypted with Android Keystore and remains on this device.",
                color = palette.textSecondary,
                fontFamily = OracleSans,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.surface)
                .border(1.dp, palette.outlineSoft, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CROSSMEMORY", color = palette.accent, fontFamily = OracleMono,
                        fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                    Text("Remember across conversations", color = palette.text,
                        fontFamily = OracleSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                androidx.compose.material3.Switch(
                    checked = crossMemoryEnabled,
                    onCheckedChange = {
                        crossMemoryEnabled = it
                        crossMemoryStore.setEnabled(it)
                    }
                )
            }
            Text(
                "When enabled, ORACLE can save useful long-term preferences, project context and decisions locally, then reuse relevant memories in future chats. Gemini proposes memories; ORACLE validates and removes the hidden memory trailer before showing the answer.",
                color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stored locally; relevant memory excerpts are included in Gemini requests",
                    modifier = Modifier.weight(1f), color = palette.textTertiary,
                    fontFamily = OracleSans, fontSize = 10.sp, lineHeight = 14.sp)
                Text(
                    "CLEAR",
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        crossMemoryStore.clear()
                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = palette.error, fontFamily = OracleMono, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.surface)
                .border(1.dp, palette.outlineSoft, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OracleGlyphIcon(OracleGlyph.Key, Modifier.size(17.dp), palette.textSecondary)
                Spacer(Modifier.width(9.dp))
                Text(
                    "API KEY",
                    color = palette.text,
                    fontFamily = OracleMono,
                    fontSize = 10.sp,
                    letterSpacing = 0.55.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                if (stored) {
                    Text(
                        "ENCRYPTED",
                        color = palette.success,
                        fontFamily = OracleMono,
                        fontSize = 9.sp,
                        letterSpacing = 0.35.sp
                    )
                }
            }

            if (savedKeyLabels.isNotEmpty()) {
                Text("SAVED KEYS · ACTIVE: ${activeKeyLabel ?: "LEGACY"}", color = palette.textTertiary,
                    fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.7.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    savedKeyLabels.forEach { label ->
                        Text(
                            label + if (label == activeKeyLabel) "  ✓" else "",
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (label == activeKeyLabel) palette.accentSoft else palette.canvas)
                                .border(1.dp, if (label == activeKeyLabel) palette.accent.copy(alpha = 0.5f) else palette.outlineSoft, RoundedCornerShape(10.dp))
                                .clickable {
                                    store.setActiveKey(label)
                                    activeKeyLabel = label
                                    status = "Active Gemini key switched to $label."
                                }.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = if (label == activeKeyLabel) palette.accent else palette.textSecondary,
                            fontFamily = OracleSans, fontSize = 11.sp
                        )
                    }
                }
            }
            Text("KEY LABEL", color = palette.textTertiary, fontFamily = OracleMono, fontSize = 9.sp, letterSpacing = 0.7.sp)
            BasicTextField(
                value = keyLabel,
                onValueChange = { keyLabel = it.take(32) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(palette.canvas).border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontFamily = OracleSans, fontSize = 12.sp),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (keyLabel.isBlank()) Text("e.g. Personal / Backup / Work", color = palette.textTertiary, fontSize = 12.sp)
                        inner()
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.canvas)
                    .border(1.dp, palette.outline, RoundedCornerShape(15.dp))
                    .padding(start = 15.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.text,
                        fontFamily = OracleMono,
                        fontSize = 12.sp
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    decorationBox = { inner ->
                        Box {
                            if (keyInput.isEmpty()) {
                                Text(
                                    if (stored) "Enter a new key to replace" else "Paste Gemini API key",
                                    color = palette.textTertiary,
                                    fontFamily = OracleSans,
                                    fontSize = 12.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                OracleIconButton(
                    glyph = if (showKey) OracleGlyph.EyeOff else OracleGlyph.Eye,
                    description = if (showKey) "Hide API key" else "Show API key",
                    onClick = { showKey = !showKey },
                    size = 40.dp
                )
            }

            OracleSolidAction(
                label = if (busy) "VERIFYING…" else "VERIFY & SECURE KEY",
                onClick = {
                    scope.launch {
                        busy = true
                        success = false
                        status = "Verifying with Google Gemini…"
                        try {
                            val enteredKey = keyInput.trim()
                            val candidate = if (enteredKey.isNotBlank()) {
                                enteredKey
                            } else {
                                withContext(Dispatchers.IO) { store.read().orEmpty() }
                            }

                            when (val result = api.validateKey(candidate)) {
                                is GeminiConnectionResult.Connected -> {
                                    if (enteredKey.isNotBlank()) {
                                        withContext(Dispatchers.IO) { store.saveNamedKey(keyLabel.trim().ifBlank { "Primary" }, candidate) }
                                        savedKeyLabels = withContext(Dispatchers.IO) { store.keyLabels() }
                                        activeKeyLabel = withContext(Dispatchers.IO) { store.activeKeyLabel() }
                                        stored = true
                                        keyInput = ""
                                    }
                                    status = result.message
                                    success = true
                                }
                                is GeminiConnectionResult.Failed -> {
                                    status = result.message
                                    success = false
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "Could not verify or securely store this credential."
                            success = false
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && (keyInput.isNotBlank() || stored)
            )

            Row(verticalAlignment = Alignment.Top) {
                OracleGlyphIcon(
                    if (success) OracleGlyph.Check else OracleGlyph.Shield,
                    Modifier.padding(top = 2.dp).size(15.dp),
                    if (success) palette.success else palette.textTertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    status,
                    modifier = Modifier.weight(1f),
                    color = if (success) palette.success else palette.textSecondary,
                    fontFamily = OracleSans,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            if (stored) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.outlineSoft)
                )
                OracleTextAction(
                    label = if (busy) "PLEASE WAIT" else "REMOVE STORED KEY",
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Removing encrypted credential…"
                            success = false
                            try {
                                withContext(Dispatchers.IO) {
                                    val active = store.activeKeyLabel()
                                    if (active != null) store.removeNamedKey(active) else store.clear()
                                }
                                savedKeyLabels = withContext(Dispatchers.IO) { store.keyLabels() }
                                activeKeyLabel = withContext(Dispatchers.IO) { store.activeKeyLabel() }
                                stored = store.hasKey()
                                keyInput = ""
                                status = "Selected key removed. Other saved keys remain available."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "Could not remove the stored key. Try again."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    accent = false
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.surface)
                .border(1.dp, palette.outlineSoft, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OracleGlyphIcon(OracleGlyph.Search, Modifier.size(17.dp), palette.textSecondary)
                Spacer(Modifier.width(9.dp))
                Text("INDEPENDENT WEB SEARCH", color = palette.text, fontFamily = OracleMono,
                    fontSize = 10.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (tavilyStored) Text("ENCRYPTED", color = palette.success, fontFamily = OracleMono, fontSize = 9.sp)
            }
            Text(
                "ORACLE uses Tavily Search as its own web-search tool. It does not use Gemini Google Search grounding.",
                color = palette.textSecondary, fontFamily = OracleSans, fontSize = 12.sp, lineHeight = 18.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(palette.canvas).border(1.dp, palette.outline, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = tavilyInput,
                    onValueChange = { tavilyInput = it },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = palette.text, fontFamily = OracleMono, fontSize = 11.sp),
                    cursorBrush = SolidColor(palette.accent),
                    visualTransformation = if (showTavilyKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    decorationBox = { inner ->
                        Box {
                            if (tavilyInput.isEmpty()) Text(
                                if (tavilyStored) "Paste a replacement Tavily key" else "Paste Tavily API key",
                                color = palette.textTertiary, fontFamily = OracleSans, fontSize = 12.sp
                            )
                            inner()
                        }
                    }
                )
                OracleIconButton(
                    glyph = if (showTavilyKey) OracleGlyph.EyeOff else OracleGlyph.Eye,
                    description = if (showTavilyKey) "Hide Tavily key" else "Show Tavily key",
                    onClick = { showTavilyKey = !showTavilyKey },
                    size = 38.dp
                )
            }
            OracleSolidAction(
                label = "SAVE SEARCH KEY ENCRYPTED",
                onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.saveTavilyKey(tavilyInput.trim()) }
                            tavilyStored = true
                            tavilyInput = ""
                            tavilyStatus = "Tavily key saved using Android Keystore encryption."
                        } catch (_: Exception) {
                            tavilyStatus = "Could not securely store the Tavily key."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = tavilyInput.isNotBlank()
            )
            Text(
                tavilyStatus,
                color = palette.textSecondary, fontFamily = OracleSans, fontSize = 11.sp, lineHeight = 16.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OracleTextAction("GET TAVILY KEY", onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://app.tavily.com/")
                        ))
                    }
                }, accent = true)
                if (tavilyStored) OracleTextAction("REMOVE KEY", onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.clearTavilyKey() }
                        tavilyStored = false
                        tavilyStatus = "Tavily key removed from this device."
                    }
                }, accent = false)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.surface)
                .border(1.dp, palette.outlineSoft, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "PRIVACY",
                color = palette.textSecondary,
                fontFamily = OracleMono,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            )
            PrivacyLine("AES-GCM encryption; key material held by Android Keystore.")
            PrivacyLine("Credential is sent to Google's Gemini API over HTTPS when a model action is invoked.")
            PrivacyLine("No API key is embedded in the APK, source code, analytics, or logs.")
            PrivacyLine("Local Python runs inside the app process with access to ORACLE app-private files and the network; it is not an OS security sandbox.")
            PrivacyLine("Every Python run and phone-file write needs fresh approval. Python source is shown before execution, and printed output is returned to Gemini.")
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    val palette = LocalOraclePalette.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = palette.textSecondary,
            fontFamily = OracleSans,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}
