package io.github.jenziner.nisaba.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jenziner.nisaba.graph
import io.github.jenziner.nisaba.net.LocalNetworkAccess

/**
 * Hinweis und Anfrage für die Android-17-Berechtigung „Lokales Netz“.
 * Zeigt nichts, wenn sie erteilt ist (bzw. `always` = true: zeigt auch den Status).
 */
@Composable
fun LocalNetworkPermission(always: Boolean = false) {
    val ctx = LocalContext.current
    val local = ctx.graph.localNet
    var tick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok; tick++
    }
    val granted = remember(tick) { local.granted() }
    if (granted) {
        if (always) Hint("Local network permission: " + if (local.enforced) "granted" else "not needed (before Android 17)")
        return
    }
    ErrorText(
        "Local network access is not allowed yet. Without it, the app cannot reach the Tiiny via USB or Wi-Fi.",
    )
    Row {
        Button(onClick = { launcher.launch(LocalNetworkAccess.PERMISSION) }) { Text("Allow") }
        TextButton(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("App info") }
    }
    if (denied) Hint(
        "Not granted. If no prompt appears, allow \"Nearby devices\" / \"Local network\" under App info → Permissions.",
    )
}
