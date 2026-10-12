package dev.omalauncher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.SizeF
import androidx.compose.runtime.mutableStateListOf
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** A stock Android widget on the home grid (a clock, the weather, a search bar), hosted the way any launcher hosts them. */
data class AppWidgetItem(
    val id: Int,
    override val page: Int,
    val provider: ComponentName,
    /** Its size in cells when it was added, from what the widget asks for. */
    override val size: Pair<Int, Int>,
    override val minSize: Pair<Int, Int>,
) : HomeItem {
    override val key get() = KEY_PREFIX + id

    companion object {
        const val KEY_PREFIX = "appwidget-"
    }
}

/**
 * The home screen's Android widgets: Android's widget host, the widgets added to it and where they sit.
 * Saved launcher-side next to the grid layout, which keeps their cells like any tile's.
 */
class AppWidgets(
    context: Context,
    private val layout: WidgetLayout,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("app_widgets", Context.MODE_PRIVATE)
    val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
    val host = AppWidgetHost(app, HOST_ID)
    val items = mutableStateListOf<AppWidgetItem>()

    /** One view per widget, kept while it's off screen so swiping pages doesn't rebuild it. */
    private val views = HashMap<Int, AppWidgetHostView>()

    init {
        (Versioned.read(prefs.getString(ITEMS, null), VERSION) as? JSONArray)?.let { array ->
            items +=
                array.mapObjects { o ->
                    val provider = ComponentName.unflattenFromString(o.getString("provider")) ?: return@mapObjects null
                    AppWidgetItem(o.getInt("id"), o.optInt("page", 1), provider, o.getInt("w") to o.getInt("h"), o.optInt("mw", 1) to o.optInt("mh", 1))
                }
        }
        // Ids the host holds that no tile uses (a configure screen backed out of mid-add) are given back.
        val ours = items.map { it.id }.toSet()
        runCatching { host.appWidgetIds }.getOrNull()?.forEach { if (it !in ours) release(it) }
    }

    fun startListening() {
        runCatching { host.startListening() }.onFailure { Log.w(TAG, "can't listen for widget updates", it) }
    }

    fun stopListening() {
        runCatching { host.stopListening() }
    }

    /** Every widget an app offers, in every profile the launcher sees. */
    fun providers(): List<AppWidgetProviderInfo> = runCatching { manager.installedProviders }.getOrDefault(emptyList())

    /**
     * The view showing [item], made with [context] (the home screen's activity) the first time;
     * null when its app is gone or hasn't come back after an update yet.
     */
    fun view(
        context: Context,
        item: AppWidgetItem,
    ): AppWidgetHostView? {
        views[item.id]?.let { return it }
        val info = manager.getAppWidgetInfo(item.id) ?: return null
        return runCatching { host.createView(context, item.id, info) }
            .onFailure { Log.w(TAG, "can't show widget ${item.provider}", it) }
            .getOrNull()
            ?.also { views[item.id] = it }
    }

    /** The home screen's activity is going: its views go with it. */
    fun clearViews() = views.clear()

    fun allocate(): Int = host.allocateAppWidgetId()

    fun release(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    /** Puts bound widget [id] on [page], at the size it asks for; the grid finds it a spot. */
    fun add(
        id: Int,
        info: AppWidgetProviderInfo,
        page: Int,
        metrics: DisplayMetrics,
    ) {
        val (size, min) = cells(info, metrics)
        items += AppWidgetItem(id, page, info.provider, size, min)
        save()
    }

    fun remove(item: AppWidgetItem) {
        items.removeAll { it.id == item.id }
        views.remove(item.id)
        release(item.id)
        save()
    }

    fun move(
        key: String,
        page: Int,
    ) {
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return
        items[i] = items[i].copy(page = page)
        save()
    }

    /** Tells the widget its tile's size in dp, so it lays itself out for it, as launchers do on a resize. */
    fun resized(
        item: AppWidgetItem,
        widthDp: Float,
        heightDp: Float,
    ) {
        val view = views[item.id] ?: return
        if (widthDp <= 0f || heightDp <= 0f) return
        if (Build.VERSION.SDK_INT >= 31) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
        }
    }

    /** The cells [info] asks for, and the fewest it shrinks to: Android 12's target cells, else its sizes in pixels. */
    fun cells(
        info: AppWidgetProviderInfo,
        metrics: DisplayMetrics,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val (tileW, tileH) = layout.tilePx(1 to 1, metrics)
        val gap = Math.round(6 * metrics.density)
        fun across(px: Int) = ceil((px + gap) / (tileW + gap).toFloat()).toInt().coerceIn(1, GRID_COLUMNS)
        fun down(px: Int) = ceil((px + gap) / (tileH + gap).toFloat()).toInt().coerceIn(1, MAX_ROWS)
        val size =
            if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
                info.targetCellWidth.coerceIn(1, GRID_COLUMNS) to info.targetCellHeight.coerceIn(1, MAX_ROWS)
            } else {
                across(info.minWidth) to down(info.minHeight)
            }
        val resizes = info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
        val min =
            if (resizes && info.minResizeWidth > 0 && info.minResizeHeight > 0) {
                minOf(across(info.minResizeWidth), size.first) to minOf(down(info.minResizeHeight), size.second)
            } else {
                1 to 1
            }
        return size to min
    }

    private fun save() {
        val array = JSONArray()
        for (i in items) {
            array.put(
                JSONObject()
                    .put("id", i.id)
                    .put("provider", i.provider.flattenToString())
                    .put("page", i.page)
                    .put("w", i.size.first)
                    .put("h", i.size.second)
                    .put("mw", i.minSize.first)
                    .put("mh", i.minSize.second),
            )
        }
        prefs.edit().putString(ITEMS, Versioned.write(VERSION, array)).apply()
    }

    companion object {
        /** This launcher's widget host, as Android knows it; its widgets stay bound to it across restarts. */
        const val HOST_ID = 0x0C0B1A
        private const val ITEMS = "items"
        private const val VERSION = 1
        private const val MAX_ROWS = 12
        private const val TAG = "app-widgets"
    }
}
