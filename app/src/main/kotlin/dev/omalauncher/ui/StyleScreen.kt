package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.IconPack
import dev.omalauncher.style.StyleStore
import dev.omalauncher.style.Themes
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * settings › style: the launcher's colors and font (style.toml, which an agent can change for you),
 * how the home screen draws apps and, with Linux, the terminal's font and text size.
 */
@Composable
fun StyleScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val store = runtime.style
    val linux by runtime.bootstrap.state.collectAsState()
    val installed = linux == LinuxState.Installed
    OverlayPanel("style", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            StyleFileSection(store, installed) {
                val agent = runtime.agents.default
                if (agent != null) runtime.terminal(StyleStore.editCommand(agent.bin), "Style", agent)
            }
            Heading("home screen")
            StyleOption("icons", listOf("monochrome", "colored"), if (LauncherStyle.monochromeIcons) 0 else 1) { store.setMonochromeIcons(it == 0) }
            StyleOption("home apps", listOf("boxed", "icon only"), if (LauncherStyle.bareAppIcons) 1 else 0) { store.setBareAppIcons(it == 1) }
            StyleOption("recent apps", listOf("shown", "hidden"), if (LauncherStyle.recentApps) 0 else 1) { store.setRecentApps(it == 0) }
            StyleOption("corners", Corners.entries.map { it.label }, LauncherStyle.corners.ordinal) { store.setCorners(Corners.entries[it]) }
            IconPackOption(runtime)
            if (installed) TerminalSection(runtime.terminalFont)
        }
    }
}

/** The palette widgets use by name, as swatches, and the font: what style.toml sets, or Copland OS's own. */
@Composable
private fun StyleFileSection(
    store: StyleStore,
    linux: Boolean,
    onEdit: () -> Unit,
) {
    Heading("colors & font") {
        if (linux) BracketAction("edit with agent", Palette.accent, onEdit)
    }
    val themes = Themes.ALL.keys.toList()
    StyleOption("theme", themes.map { Themes.LABELS[it] ?: it }, themes.indexOf(store.theme).coerceAtLeast(0)) { store.pickTheme(themes[it]) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val named =
            listOf(
                "background" to Palette.background,
                "surface" to Palette.surface,
                "border" to Palette.border,
                "foreground" to Palette.foreground,
                "muted" to Palette.muted,
                "accent" to Palette.accent,
            ) + listOf("red", "yellow", "green", "cyan", "blue", "magenta").map { it to (Palette.named(it) ?: Color.Gray) }
        for ((name, color) in named) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).frame(1.dp, Palette.border, color, radius = 0.dp))
                BasicText(name, Modifier.padding(start = 6.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
    }
    val font = store.overrides["font"]?.takeIf { it.isNotBlank() } ?: "mono"
    BasicText(
        "font  $font" + (store.missingFont?.let { " · not found, using mono" } ?: ""),
        Modifier.padding(top = 12.dp),
        style = Mono.copy(color = if (store.missingFont != null) Palette.red else Palette.foreground),
    )
    BasicText(
        when {
            !linux -> "The theme's colors. Any of them can be changed in ~/.config/omalauncher/style.toml, which comes with Linux."
            store.overrides.keys.none { it != "theme" && it != "font" } -> "The theme's colors · change any in ~/.config/omalauncher/style.toml"
            else -> "The theme, with ~/.config/omalauncher/style.toml's colors · saving it restyles everything at once"
        },
        Modifier.padding(top = 4.dp),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
}

/** Icon packs for Android apps, in the format Nova and Lawnchair read; "none" keeps the apps' own icons. */
@Composable
private fun IconPackOption(runtime: Runtime) {
    val context = LocalContext.current
    val packs by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { IconPack.installed(context) } }
    val names = listOf("none") + packs.map { it.second }
    val selected = packs.indexOfFirst { it.first == LauncherStyle.iconPack } + 1
    ChipOption("icon pack", names, selected) { i -> runtime.setIconPack(if (i == 0) null else packs[i - 1].first) }
    if (packs.isEmpty()) {
        BasicText(
            "none installed · any pack made for Nova or Lawnchair works",
            Modifier.padding(start = 104.dp, bottom = 4.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
    }
}

/** The terminals' font and the size they open at, on top of Android's font size; see [TerminalFont]. */
@Composable
private fun TerminalSection(font: TerminalFont) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading("terminal")
    ChipOption("font", font.families, font.families.indexOf(font.family)) { font.select(font.families[it]) }
    BasicText(
        "terminals opened from now on · more with pacman, like ttf-cascadia-code",
        Modifier.padding(start = 104.dp, bottom = 4.dp),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    StyleOption("text", TerminalText.entries.map { it.label }, font.text.ordinal) { font.select(TerminalText.entries[it]) }
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    BasicText(
        "${TerminalFont.format(font.size).removeSuffix(".0")} pt · $columns columns in portrait",
        Modifier.padding(start = 104.dp, top = 2.dp),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
}

/** A section's title in the accent, set apart from the muted labels of the options under it; [actions] go at the far end. */
@Composable
private fun Heading(
    text: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f), style = Mono.copy(color = Palette.accent), softWrap = false)
        actions()
    }
}

/** Names that run long (fonts, icon packs): each is a chip of its own, and they wrap rather than scroll out of sight. */
@Composable
private fun ChipOption(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText(label, Modifier.widthIn(min = 104.dp).padding(top = 8.dp, end = 8.dp), style = Mono.copy(color = Palette.muted), softWrap = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, option ->
                val active = i == selected
                BasicText(
                    option,
                    Modifier
                        .frame(1.dp, Palette.border, if (active) Palette.accent else Color.Transparent, radius = 12.dp)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                    softWrap = false,
                )
            }
        }
    }
}

/** A labeled segmented choice: the picked option fills with the accent color. */
@Composable
private fun StyleOption(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.widthIn(min = 104.dp).padding(end = 8.dp), style = Mono.copy(color = Palette.muted), softWrap = false)
        // At a large Android font size the options may not fit: they scroll rather than wrap.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
                options.forEachIndexed { i, option ->
                    val active = i == selected
                    BasicText(
                        option,
                        Modifier
                            .background(if (active) Palette.accent else Color.Transparent)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                        softWrap = false,
                    )
                }
            }
        }
    }
}
