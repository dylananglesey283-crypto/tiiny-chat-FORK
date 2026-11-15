package io.github.jenziner.nisaba.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.SoulRead
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SoulScreen(nav: Nav, project: ProjectRef) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<SoulRead?>(null) }
    var original by remember { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(project) {
        val r = withContext(Dispatchers.IO) { g.storage()?.second?.readSoul(project) }
            ?: SoulRead.Error("No project folder", null)
        loaded = r
        original = when (r) { is SoulRead.Present -> r.content; is SoulRead.Empty -> r.content; else -> "" }
        text = original
    }

    val editable = loaded != null && loaded !is SoulRead.Error
    val dirty = editable && text != original
    val leave = { if (dirty) confirmLeave = true else nav.pop() }
    BackHandler { leave() }

    Page("Project instructions", onBack = { leave() }) { pad ->
        ScrollColumn(pad) {
            Hint("SOUL.md in project \"${project.project.name}\". Optional. Where it conflicts with the general app instructions, it takes precedence.")
            when (val l = loaded) {
                null -> Hint("Reading …")
                is SoulRead.Missing -> Hint("SOUL.md does not exist. Saving creates it.")
                is SoulRead.Empty -> Hint("SOUL.md exists but is empty.")
                is SoulRead.Present -> {}
                is SoulRead.Error -> ErrorText(
                    "SOUL.md cannot be read: ${l.detail}. This is not the same as a missing file; " +
                        "editing is locked so that nothing is overwritten.",
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; message = null },
                enabled = editable && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(vertical = 8.dp),
                label = { Text("Content (Markdown)") },
            )
            Hint(Texts.SOUL_NOTE)
            message?.let { Hint(it) }
            error?.let { ErrorText(it) }
            Row(Modifier.padding(top = 8.dp)) {
                Button(enabled = dirty && !saving, onClick = { confirmSave = true }) { Text("Save") }
                OutlinedButton(onClick = { leave() }, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
            }
        }
    }

    if (confirmSave) Confirm(
        title = "Save SOUL.md?",
        text = Texts.SOUL_NOTE,
        confirm = "Save",
        onConfirm = {
            confirmSave = false
            val l = loaded ?: return@Confirm
            saving = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { g.storage()?.second?.writeSoul(project, l, text) }
                saving = false
                when (r) {
                    is StoreResult.Ok -> {
                        loaded = r.value; original = text; error = null; message = "Saved."
                    }
                    null -> error = "No project folder."
                    else -> error = "Not saved: ${Texts.store(r)}. Your text stays in the editor."
                }
            }
        },
        onDismiss = { confirmSave = false },
    )
    if (confirmLeave) Confirm(
        title = "Discard changes?",
        text = "Your changes to SOUL.md are not saved.",
        confirm = "Discard",
        onConfirm = { confirmLeave = false; nav.pop() },
        onDismiss = { confirmLeave = false },
        dismiss = "Keep editing",
    )
}
