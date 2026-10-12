package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.newAppCommand
import dev.omalauncher.widgets.newWidgetCommand

@Composable
internal fun EditBar(content: @Composable () -> Unit) {
    Row(
        Modifier
            .naviWindow(radius = 16.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun EditAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = Palette.foreground,
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText(label, style = Mono.copy(color = color, fontSize = TextSize.Body))
    }
}

/**
 * The long-press menu for empty home space. Drawn by the launcher over the whole
 * screen (not inside the home page's system-bar padding) so its scrim covers the
 * status and navigation bars too.
 */
@Composable
fun HomeMenu(
    runtime: Runtime,
    state: LauncherState,
) {
    if (!state.homeMenuOpen) return
    val engine = runtime.widgets
    val agent = runtime.agents.default
    val agentName = agent?.name?.lowercase() ?: "an agent"
    // These need Linux; without it they open its install sheet.
    val linux = runtime.bootstrap.isInstalled
    Menu(onDismiss = { state.homeMenuOpen = false }) {
        MenuItem("new widget with $agentName") {
            state.homeMenuOpen = false
            when {
                !linux -> state.linuxOpen = true
                agent == null -> state.agentsOpen = true
                else -> runtime.terminal(newWidgetCommand(agent.bin), "New widget", agent)
            }
        }
        MenuItem("new app with $agentName") {
            state.homeMenuOpen = false
            when {
                !linux -> state.linuxOpen = true
                agent == null -> state.agentsOpen = true
                else -> runtime.terminal(newAppCommand(agent.bin), "New app", agent)
            }
        }
        MenuItem("widget store") {
            state.homeMenuOpen = false
            state.storeOpen = true
        }
        MenuItem("AUR app store") {
            state.homeMenuOpen = false
            if (linux) state.packagesOpen = true else state.linuxOpen = true
        }
        MenuItem("style") {
            state.homeMenuOpen = false
            state.styleOpen = true
        }
    }
}

@Composable
private fun Menu(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.overlay)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // A Navi window, like the panels it opens.
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .naviWindow()
                .clickable(enabled = false) {},
        ) {
            NaviBar("NAVI", onDismiss)
            Column(Modifier.padding(8.dp)) { content() }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { BasicText(label, style = Mono.copy(fontSize = TextSize.Item)) }
}
