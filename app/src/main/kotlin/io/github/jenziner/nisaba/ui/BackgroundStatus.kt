package io.github.jenziner.nisaba.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jenziner.nisaba.background.AnswerService

/**
 * Settings section "Background": explains the behaviour and shows whether the
 * notification is allowed and whether battery use is unrestricted. Both are
 * optional; answers run on in the background through the foreground service.
 */
@Composable
fun BackgroundStatus() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notif = remember(tick) { AnswerService.notificationsAllowed(ctx) }
    val battery = remember(tick) { AnswerService.batteryUnrestricted(ctx) }
    Hint("Running answers continue when the screen locks or you switch apps. A notification is shown meanwhile (with Stop).")
    Hint("Notifications: " + if (notif) "allowed" else "not allowed – answers still continue, but without a visible notification")
    Hint("Battery use: " + if (battery) "unrestricted" else "optimized (default) – usually fine; choose \"Unrestricted\" if answers stop with the screen locked")
    Row {
        TextButton(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("App info") }
    }
}
