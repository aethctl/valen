/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberSystemShare(): (subject: String, text: String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { subject, text ->
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

actual val canKeepScreenOn: Boolean = true

@Composable
actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled = enabled, onBack = onBack)

/**
 * The phone's Files app at the folder, for a folder in shared storage; null for one inside an
 * app's own storage (`Android/data`), which no file manager may open.
 */
@Composable
actual fun rememberFolderOpener(path: String): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context, path) { PhoneFiles.opener(context, path) }
}

/**
 * The system's own chooser (the Files app), after Android's question for the phone's music: with it
 * Valen reads the songs a playlist names and walks a chosen folder; without it the chosen list still
 * comes in, its songs looked for on the services.
 */
@Composable
actual fun rememberLocalPicker(onPicked: (String) -> Unit, onAccess: () -> Unit): ((folder: Boolean) -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = rememberUpdatedState(onPicked)
    val access = rememberUpdatedState(onAccess)
    val deliver: (Uri?, Boolean) -> Unit = { uri, folder ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { PhoneFiles.pathOf(context, uri, folder) }
            if (path != null) picked.value(path)
            else Toast.makeText(context, "Valen cannot read that place. Choose one on this phone.", Toast.LENGTH_LONG).show()
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { deliver(it, false) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { deliver(it, true) }
    val pending = remember { booleanArrayOf(false) }
    fun open(folder: Boolean) {
        runCatching { if (folder) tree.launch(null) else file.launch(PhoneFiles.PickTypes) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        if (answers.values.any { it }) access.value()
        open(pending[0])
    }
    return remember {
        { folder ->
            pending[0] = folder
            val missing = PhoneFiles.permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) open(folder) else ask.launch(missing.toTypedArray())
        }
    }
}
