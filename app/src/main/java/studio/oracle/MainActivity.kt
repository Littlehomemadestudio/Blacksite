package studio.oracle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import studio.oracle.ui.HandcraftedLogo
import studio.oracle.ui.LibraryScreen
import studio.oracle.ui.OracleGlyph
import studio.oracle.ui.OracleIconButton
import studio.oracle.ui.OracleScreen
import studio.oracle.ui.SettingsScreen
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
    SETTINGS
}

@Composable
private fun OracleRoot() {
    var destination by remember { mutableStateOf(OracleDestination.CHAT) }

    BackHandler(enabled = destination != OracleDestination.CHAT) {
        destination = OracleDestination.CHAT
    }

    when (destination) {
        OracleDestination.CHAT -> OracleScreen(
            onOpenLibrary = { destination = OracleDestination.LIBRARY },
            onOpenSettings = { destination = OracleDestination.SETTINGS }
        )

        OracleDestination.LIBRARY -> OracleSection(
            title = "Library",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            LibraryScreen()
        }

        OracleDestination.SETTINGS -> OracleSection(
            title = "Settings",
            onBack = { destination = OracleDestination.CHAT }
        ) {
            SettingsScreen()
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
