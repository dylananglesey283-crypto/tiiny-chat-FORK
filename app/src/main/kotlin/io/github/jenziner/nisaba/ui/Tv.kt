package io.github.jenziner.nisaba.ui

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** True when the app runs on a television (Android TV / Google TV), controlled by a remote. */
val LocalIsTv = compositionLocalOf { false }

fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Keeps content inside the TV safe area (overscan) so nothing sits at the screen edge. */
val TvSafePadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * Like `clickable`, but draws a clear focus ring. With a remote (D-pad) the focused item is the
 * only feedback the user has, so it has to be unmistakable.
 */
fun Modifier.focusClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    this
        .border(if (focused) 3.dp else 0.dp, ring, RoundedCornerShape(8.dp))
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick)
}

/**
 * Launches a system picker. Returns false (instead of crashing) when the device has no app that
 * can handle it, which is common on TVs.
 */
fun <I> ManagedActivityResultLauncher<I, *>.tryLaunch(input: I): Boolean =
    try { launch(input); true } catch (_: ActivityNotFoundException) { false }

@Composable
fun rememberIsTv(): Boolean = LocalIsTv.current || isTelevision(LocalContext.current)
