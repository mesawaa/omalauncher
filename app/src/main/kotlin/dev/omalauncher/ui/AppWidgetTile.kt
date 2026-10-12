package dev.omalauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.widgets.AppWidgetItem
import dev.omalauncher.widgets.AppWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A stock Android widget in its tile: the widget draws itself, edge to edge, and takes its own taps. */
@Composable
internal fun AppWidgetTile(
    item: AppWidgetItem,
    widgets: AppWidgets,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val view = remember(item.id) { widgets.view(context, item) }
    Box(modifier.widgetTile(ownBackground = true), contentAlignment = Alignment.Center) {
        if (view == null) {
            // Its app was removed, or is being updated.
            BasicText("widget unavailable", Modifier.padding(8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        } else {
            AndroidView(
                // A view another page showed before is moved here rather than made again.
                factory = {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxSize().onSizeChanged { widgets.resized(item, it.width / density, it.height / density) },
            )
        }
    }
}

/** Every widget the phone's apps offer, by app, with a preview where the widget has one; a tap adds it. */
@Composable
internal fun AppWidgetPicker(
    widgets: AppWidgets,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val groups by produceState<List<Pair<String, List<AppWidgetProviderInfo>>>?>(null) {
        value =
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                widgets
                    .providers()
                    .groupBy { it.provider.packageName }
                    .map { (pkg, list) ->
                        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                        app to list.sortedBy { it.loadLabel(pm).lowercase() }
                    }.sortedBy { it.first.lowercase() }
            }
    }
    OverlayPanel("android widgets", onDismiss, contentWidth = ReadingWidth) {
        val list = groups
        when {
            list == null -> BasicText("…", style = Mono.copy(color = Palette.muted))
            list.isEmpty() -> BasicText("No app on this phone offers a widget.", style = Mono.copy(color = Palette.muted))
            else ->
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    for ((app, infos) in list) {
                        item { SectionLabel(app) }
                        items(infos) { info -> ProviderRow(widgets, info) { onPick(info) } }
                    }
                }
        }
    }
}

@Composable
private fun ProviderRow(
    widgets: AppWidgets,
    info: AppWidgetProviderInfo,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val label = remember(info) { runCatching { info.loadLabel(context.packageManager) }.getOrDefault(info.provider.shortClassName) }
    val cells = remember(info) { widgets.cells(info, metrics).first }
    val preview by produceState<ImageBitmap?>(null, info) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    // Widgets made for Android 12 often have only a layout preview; their app's icon stands in.
                    val d = info.loadPreviewImage(context, metrics.densityDpi) ?: info.loadIcon(context, metrics.densityDpi) ?: return@runCatching null
                    val max = (120 * metrics.density).toInt()
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: max
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: max
                    val scale = minOf(1f, max / maxOf(w, h).toFloat())
                    val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, bmp.width, bmp.height)
                    d.draw(Canvas(bmp))
                    bmp.asImageBitmap()
                }.getOrNull()
            }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 120.dp, height = 84.dp).frame(1.dp, Palette.border, Palette.surface, radius = 8.dp).padding(6.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText("${cells.first}×${cells.second}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}
