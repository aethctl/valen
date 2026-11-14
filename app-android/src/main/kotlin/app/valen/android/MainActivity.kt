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

            // Back closes the sign-in first; the app's own back follows.
            LaunchedEffect(Unit) {
                AndroidSignInHub.request.collect { current ->
                    if (current == null) hidden.post { ValenRuntime.attachHidden(hidden) }
                }
            }
        }

        ValenRuntime.attachHidden(hidden)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AndroidSignInHub.request.value != null) {
                    AndroidSignInHub.close()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = intent.getStringExtra("route")
        query.value = intent.getStringExtra("query")
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
}
