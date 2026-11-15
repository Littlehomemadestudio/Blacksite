package studio.oracle.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.oracle.data.AgentToolSettings
import studio.oracle.data.GeminiModel
import studio.oracle.ui.theme.LocalOraclePalette
import studio.oracle.ui.theme.OracleMono
import studio.oracle.ui.theme.OracleSans

@Composable
fun AgentCapabilitiesPanel(
    settings: AgentToolSettings,
    model: GeminiModel?,
    folderGranted: Boolean,
    onGoogleSearch: (Boolean) -> Unit,
    onUrlContext: (Boolean) -> Unit,
    onCloudPython: (Boolean) -> Unit,
    onLocalPython: (Boolean) -> Unit,
    onPhoneFiles: (Boolean) -> Unit,
    onGrantFolder: () -> Unit,
    onRevokeFolder: () -> Unit,
    onThinking: (String) -> Unit,
    onContext: (Int) -> Unit,
    onOutput: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    val modelIsGemini3 = model?.name?.startsWith("gemini-3", ignoreCase = true) == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(palette.surface)
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(19.dp))
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "AGENT CAPABILITIES",
                    color = palette.text,
                    fontFamily = OracleMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.9.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Tools are invoked by Gemini when useful",
                    color = palette.textTertiary,
                    fontFamily = OracleSans,
                    fontSize = 10.sp
                )
            }
            Text(
                if (settings.googleSearch || settings.cloudCodeExecution || settings.localPython || settings.phoneFiles) "TOOLS ON" else "TOOLS OFF",
                color = palette.accent,
                fontFamily = OracleMono,
                fontSize = 9.sp
            )
        }

        CapabilityToggle(
            "Google Search",
            "Search the live web and return grounded sources",
            settings.googleSearch,
            onGoogleSearch
        )
        CapabilityToggle(
            "URL Context",
            "Open and analyze a URL supplied in the conversation",
            settings.urlContext,
            onUrlContext
        )
        CapabilityToggle(
            "Google Python",
            "Google-hosted code execution for analysis",
            settings.cloudCodeExecution,
            onCloudPython
        )
        CapabilityToggle(
            "Local Python 3",
            "Embedded on-device interpreter; restricted, no shell or network",
            settings.localPython,
            onLocalPython
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Phone files",
                        color = palette.text,
                        fontFamily = OracleSans,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (folderGranted) "One folder is granted · reads and lists are scoped" else "Choose one folder to grant scoped access",
                        color = palette.textTertiary,
                        fontFamily = OracleSans,
                        fontSize = 10.sp
                    )
                }
                CapabilityChip(
                    label = if (folderGranted) "CHANGE FOLDER" else "GRANT FOLDER",
                    active = folderGranted,
                    onClick = onGrantFolder
                )
                if (folderGranted) {
                    Spacer(Modifier.width(6.dp))
                    CapabilityChip(
                        label = "REVOKE",
                        active = false,
                        onClick = onRevokeFolder
                    )
                    Spacer(Modifier.width(6.dp))
                    CapabilityChip(
                        label = if (settings.phoneFiles) "ON" else "OFF",
                        active = settings.phoneFiles,
                        onClick = { onPhoneFiles(!settings.phoneFiles) }
                    )
                }
            }
            if (folderGranted) {
                Text(
                    "ORACLE can list/read files and request new files. Every new file needs your approval.",
                    color = palette.textSecondary,
                    fontFamily = OracleSans,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SettingHeader("THINKING DEPTH", settings.thinkingLevel.uppercase())
            ChipRow(
                values = listOf("low", "medium", "high"),
                selected = settings.thinkingLevel,
                onSelect = onThinking,
                labels = mapOf("low" to "LOW", "medium" to "MEDIUM", "high" to "HIGH")
            )
            Text(
                "Mapped to thinkingLevel on Gemini 3 and thinkingBudget on Gemini 2.5.",
                color = palette.textTertiary,
                fontFamily = OracleSans,
                fontSize = 10.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SettingHeader("CONVERSATION CONTEXT", "${settings.contextMessages} MESSAGES")
            ChipRow(
                values = listOf(12, 20, 32, 48),
                selected = settings.contextMessages,
                onSelect = onContext,
                labels = mapOf(12 to "12", 20 to "20", 32 to "32", 48 to "48")
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SettingHeader("MAX OUTPUT TOKENS", "${settings.maxOutputTokens / 1000}K")
            ChipRow(
                values = listOf(4096, 8192, 16384, 32768),
                selected = settings.maxOutputTokens,
                onSelect = onOutput,
                labels = mapOf(4096 to "4K", 8192 to "8K", 16384 to "16K", 32768 to "32K")
            )
        }

        val inputLimit = model?.inputTokenLimit
        val outputLimit = model?.outputTokenLimit
        if (inputLimit != null || outputLimit != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.canvas)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SELECTED MODEL LIMITS",
                    color = palette.textTertiary,
                    fontFamily = OracleMono,
                    fontSize = 8.sp,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${inputLimit?.let(::compactTokens) ?: "—"} IN  ·  ${outputLimit?.let(::compactTokens) ?: "—"} OUT",
                    color = palette.text,
                    fontFamily = OracleMono,
                    fontSize = 9.sp
                )
            }
        }

        val customNeedsGemini3 = (settings.localPython || settings.phoneFiles) &&
            (settings.googleSearch || settings.cloudCodeExecution) && !modelIsGemini3
        if (customNeedsGemini3) {
            Text(
                "This model cannot combine local tools with Google's built-in tools in one call. Switch to an available Gemini 3 model, or turn off Google Search and Google Python to let this model use local tools.",
                color = palette.textSecondary,
                fontFamily = OracleSans,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        } else {
            Text(
                "Google Search and Google Python run in Google's managed environment. Local Python runs on this phone. Context caching is handled automatically by compatible Gemini models; there is no manual cache switch.",
                color = palette.textTertiary,
                fontFamily = OracleSans,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun CapabilityToggle(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val palette = LocalOraclePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.canvas)
            .clickable { onChange(!enabled) }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontFamily = OracleSans, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.textTertiary, fontFamily = OracleSans, fontSize = 9.sp, lineHeight = 13.sp)
        }
        CapabilityChip(if (enabled) "ON" else "OFF", enabled) { onChange(!enabled) }
    }
}

@Composable
private fun SettingHeader(title: String, selected: String) {
    val palette = LocalOraclePalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = palette.textTertiary, fontFamily = OracleMono, fontSize = 8.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
        Text(selected, color = palette.accent, fontFamily = OracleMono, fontSize = 9.sp)
    }
}

@Composable
private fun <T> ChipRow(
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labels: Map<T, String>
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            CapabilityChip(
                label = labels[value] ?: value.toString(),
                active = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CapabilityChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalOraclePalette.current
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.accentSoft else palette.canvas)
            .border(
                1.dp,
                if (active) palette.accent.copy(alpha = 0.35f) else palette.outlineSoft,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        color = if (active) palette.accent else palette.textSecondary,
        fontFamily = OracleMono,
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun compactTokens(value: Int): String =
    if (value >= 1_000_000) "${value / 1_000_000}M"
    else if (value >= 1_000) "${value / 1_000}K"
    else value.toString()
