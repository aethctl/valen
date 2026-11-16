/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import app.valen.host.android.AndroidSignInHub
import app.valen.ui.ValenApp

/**
 * The one screen: the Compose app, with the service's own sign-in page over it when a sign-in asks
 * for one, and a 1 px container that keeps the hidden WebViews attached to a window.
 */
class MainActivity : ComponentActivity() {
    private val route = mutableStateOf<String?>(null)
    private val query = mutableStateOf<String?>(null)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var hiddenContainer: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferFastestRefresh()
        // Edge to edge on every Android version: the Compose UI pads itself (safeDrawing), and the
        // sign-in page keeps clear of the bars itself. 15+ enforces it; 14 and older do not.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        route.value = intent.getStringExtra("route")
        query.value = intent.getStringExtra("query")
        askNotificationPermission()

        val root = FrameLayout(this)
        val compose = ComposeView(this)
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // A WebView that is never attached to a window runs no media: a 1 px, invisible box keeps
        // the hidden pages and the Web Player in a window without showing anything.
        val hidden = FrameLayout(this)
        root.addView(hidden, ViewGroup.LayoutParams(1, 1))
        setContentView(root)
        hiddenContainer = hidden

        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ValenApp(
                    coordinator = ValenRuntime.coordinator,
                    playlists = ValenRuntime.playlists,
                    startRoute = route.value,
                    startQuery = query.value,
                )

                val request by AndroidSignInHub.request.collectAsState()
                request?.let { req ->
                    key(req) {
                        AndroidView(
                            factory = { context ->
                                FrameLayout(context).apply {
                                    setBackgroundColor(BACKGROUND)
                                    req.attach(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // The sign-in is the service's own page; only a way out is ours.
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(SCRIM)
                                .clickable { AndroidSignInHub.close() }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            BasicText(
                                stringResource(R.string.valen_close),
                                style = TextStyle(color = Color(0xFFF4F4F5), fontSize = 15.sp),
                            )
                        }
                    }
                }
            }

            // Back closes the sign-in first; the app's own back follows. The app's handlers (the
            // song menu, the sheets, a place back to Home) register as the UI composes, which puts
            // them above any callback added earlier, so the sign-in's is added again on top each
            // time a sign-in opens.
            LaunchedEffect(Unit) {
                AndroidSignInHub.request.collect { current ->
                    signInBack.remove()
                    if (current == null) {
                        hidden.post { ValenRuntime.attachHidden(hidden) }
                    } else {
                        onBackPressedDispatcher.addCallback(this@MainActivity, signInBack)
                    }
                }
            }
        }

        ValenRuntime.attachHidden(hidden)

    }

    /** Back while a service's sign-in page is up: it closes the page, nothing else. */
    private val signInBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            AndroidSignInHub.close()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = intent.getStringExtra("route")
        query.value = intent.getStringExtra("query")
    }

    override fun onStop() {
        // Android may end the process any time after this without another word: the listening
        // counted so far goes to disk now.
        runCatching { ValenRuntime.coordinator.stats.flush() }
        runCatching { ValenRuntime.coordinator.saveSession() }
        super.onStop()
    }

    override fun onDestroy() {
        // A full-screen sign-in page was attached to this activity: let it go before the window does.
        hiddenContainer?.let { container ->
            AndroidSignInHub.request.value?.release()
            ValenRuntime.detachHidden(container)
        }
        hiddenContainer = null
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private companion object {
        val BACKGROUND: Int = 0xFF0A0A0B.toInt()
        val SCRIM: Color = Color(0xE61A1A1E)
    }

    override fun onResume() {
        super.onResume()
        // The screen's own setting may have changed while the app was away (60 ↔ 120 Hz).
        preferFastestRefresh()
    }

    /**
     * Runs at the screen's fastest refresh rate (90, 120, 144 Hz…) at its current resolution.
     * Several phone makers keep an app at 60 Hz unless it asks; Compose draws at whatever rate
     * the window gets. The app asks for frames only while something moves, so the faster rate
     * costs little when nothing does.
     */
    private fun preferFastestRefresh() {
        runCatching {
            val shown = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val now = shown?.mode ?: return
            val fastest = shown.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (window.attributes.preferredDisplayModeId == fastest.modeId) return
            window.attributes = window.attributes.also { it.preferredDisplayModeId = fastest.modeId }
        }
    }
}
