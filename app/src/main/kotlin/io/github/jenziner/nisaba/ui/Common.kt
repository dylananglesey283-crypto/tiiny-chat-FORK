package io.github.jenziner.nisaba.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenziner.nisaba.R

/** Material-Symbol aus res/drawable. [desc] ist die Beschriftung für Bildschirmleser. */
@Composable
fun Ico(@DrawableRes id: Int, desc: String?, tint: Color = Color.Unspecified, size: Int = 24) {
    Icon(
        painterResource(id), contentDescription = desc, modifier = Modifier.size(size.dp),
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}

@Composable
fun IconBtn(@DrawableRes id: Int, desc: String, enabled: Boolean = true, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Ico(id, desc, tint) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    when {
                        titleContent != null -> titleContent()
                        onTitleClick != null -> TextButton(onClick = onTitleClick) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        }
                        else -> Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    when {
                        navigationIcon != null -> navigationIcon()
                        onBack != null -> IconBtn(R.drawable.ic_arrow_back, "Back", onClick = onBack)
                    }
                },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * „Tiiny Chat“ im Stil der TiinyVerse-Marke: Tiiny-Wortmarke und daneben
 * ein gerundeter, umrandeter Kasten mit „Chat“ in Monospace. Systemfarben.
 */
@Composable
fun Wordmark(height: Int = 22) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.tiiny_wordmark), contentDescription = "Tiiny",
            modifier = Modifier.height(height.dp).width((height * 263.16f / 92.24f).dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width((height / 3).dp))
        Surface(
            shape = RoundedCornerShape((height / 3).dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
            color = Color.Transparent,
        ) {
            Text(
                "Chat",
                modifier = Modifier.padding(horizontal = (height / 3).dp, vertical = 0.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = (height * 0.8f).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Kleiner Zustands-Chip mit Symbol; [warn] hebt ihn farblich hervor. */
@Composable
fun StatusChip(@DrawableRes icon: Int, text: String, warn: Boolean = false) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Ico(icon, null, fg, size = 16)
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Scrollbare Spalte mit Innenabstand. */
@Composable
fun ScrollColumn(pad: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
fun Confirm(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}
