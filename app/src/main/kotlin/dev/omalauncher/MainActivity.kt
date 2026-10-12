package dev.omalauncher

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.PromptRequest
import dev.omalauncher.widgets.market.Share

/** The home screen: widgets, pinned apps and the drawer; Linux windows open as their own tasks. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val state = LauncherState()

    /** An Android widget being added: its id, what it is and the page it goes on, until bound and configured. */
    private var pendingWidget: Triple<Int, AppWidgetProviderInfo, Int>? = null

    /** Android asks the user once whether the launcher may add an app's widgets. */
    private val bindWidget =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val (id, info) = pendingWidget ?: return@registerForActivityResult
            if (result.resultCode == RESULT_OK) configureWidget(id, info) else cancelWidget()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(this, Palette.isLight)
        runtime.start()
        state.addAppWidget = ::addAppWidget
        setContent {
            LauncherRoot(runtime, state)
            LaunchedEffect(Palette.isLight) { applySystemBars(this@MainActivity, Palette.isLight) }
        }
        handleDebugExec(intent)
        handleSharedWidget(intent)
        // Asked on arriving at the home screen, not over the welcome screen of a fresh install,
        // and once nothing else is in front: not over the notification permission dialog that
        // opting into Linux there brings up.
        lifecycleScope.launch {
            snapshotFlow { runtime.launcherReady }.first { it }
            withResumed { requestHomeRole() }
        }
    }

    /** Binds a new widget of [info]'s kind for [page]: at once if the user allowed it before, else Android asks. */
    private fun addAppWidget(
        info: AppWidgetProviderInfo,
        page: Int,
    ) {
        val widgets = runtime.widgets.appWidgets
        val id = widgets.allocate()
        pendingWidget = Triple(id, info, page)
        val bound = runCatching { widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)
        if (bound) {
            configureWidget(id, info)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    /** Opens the widget's own setup screen if it has one it needs; then it goes on the page. */
    private fun configureWidget(
        id: Int,
        info: AppWidgetProviderInfo,
    ) {
        val optional = Build.VERSION.SDK_INT >= 28 && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            try {
                runtime.widgets.appWidgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
                return
            } catch (e: RuntimeException) {
                Log.w("app-widgets", "can't open ${info.provider}'s setup", e)
            }
        }
        placeWidget()
    }

    private fun placeWidget() {
        val (id, info, page) = pendingWidget ?: return
        pendingWidget = null
        runtime.widgets.appWidgets.add(id, info, page, resources.displayMetrics)
    }

    private fun cancelWidget() {
        pendingWidget?.let { runtime.widgets.appWidgets.release(it.first) }
        pendingWidget = null
    }

    @Deprecated("The widget host starts configure screens for a result code, not through the result API.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE_WIDGET) if (resultCode == RESULT_OK) placeWidget() else cancelWidget()
    }

    /** Asks once to become the default home app; Android shows its own chooser. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (prefs.getBoolean("home_role_asked", false)) return
        prefs.edit().putBoolean("home_role_asked", true).apply()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
    }

    /** Debug builds: `adb shell am start -n dev.omalauncher.nothing/dev.omalauncher.MainActivity --es exec foot`. */
    private fun handleDebugExec(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("exec")?.let(runtime::launch)
    }

    /**
     * A widget link (see [Share]): from the camera, a browser, or the store page's "open in Omalauncher" button,
     * which can't keep the link's fragment and passes the data as the `widget` extra instead.
     */
    private fun handleSharedWidget(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data
        val link =
            intent.getStringExtra("widget")
                ?: data?.takeIf { it.host == Share.HOST && it.path.orEmpty().startsWith(Share.PATH) }?.toString()
                ?: return
        state.shared = link
    }

    override fun onResume() {
        super.onResume()
        runtime.wm.focus(null)
        removeStaleWindowTasks()
    }

    /** Drops Recents cards whose Linux window no longer exists (app exited or process restarted). */
    private fun removeStaleWindowTasks() {
        for (task in getSystemService(ActivityManager::class.java).appTasks) {
            val base = runCatching { task.taskInfo?.baseIntent }.getOrNull() ?: continue
            // By its data rather than its class: windows start through aliases too.
            if (base.data?.let { it.scheme == "omarchy" && it.host == "window" } != true) continue
            // A window that just closed removes its own task meanwhile; finishing it again throws.
            if (runtime.wm.windowByHandle(base.data?.lastPathSegment) == null) runCatching { task.finishAndRemoveTask() }
        }
    }

    /** Pressing Home while already home closes the menu, like other launchers. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.close()
        state.closeScreens()
        runtime.widgets.openScreen = null
        state.editing = null
        handleDebugExec(intent)
        handleSharedWidget(intent)
    }

    /** Launcher shortcuts (Super+key), then typing into a terminal tile that has the keyboard. Back stays the launcher's. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && shortcut(event.keyCode)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && runtime.terminals.active != null && runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Widgets only refresh while the home screen is visible. */
    override fun onStart() {
        super.onStart()
        runtime.widgets.appWidgets.startListening()
        runtime.widgets.homeStarted()
        runtime.screenStarted()
    }

    override fun onStop() {
        super.onStop()
        runtime.widgets.appWidgets.stopListening()
        state.closeNow()
        // An app was just opened from home: it moves to the front of the strip now, out of sight.
        runtime.usage.settle()
        runtime.widgets.homeStopped()
        runtime.screenStopped()
    }

    private fun shortcut(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_A -> state.promptRequest = PromptRequest.Type
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = keyCode - KeyEvent.KEYCODE_1
            else -> return false
        }
        return true
    }

    private companion object {
        /** startAppWidgetConfigureActivityForResult's request: a widget's setup screen. */
        const val REQUEST_CONFIGURE_WIDGET = 0x0C0B
    }
}
