package io.github.jenziner.nisaba.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.Screen
import io.github.jenziner.nisaba.core.store.ChatRef
import io.github.jenziner.nisaba.core.store.Listing
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectScreen(nav: Nav, initial: ProjectRef) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(initial) }
    var chats by remember { mutableStateOf<StoreResult<Listing<ChatRef>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ChatRef?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(initial, reload) {
        val st = g.storage()
        if (st == null) { chats = null; return@LaunchedEffect }
        chats = withContext(Dispatchers.IO) {
            when (val v = st.second.reloadProject(initial)) {
                is StoreResult.Ok -> { project = v.value; st.second.listChats(v.value) }
                else -> @Suppress("UNCHECKED_CAST") (v as StoreResult<Listing<ChatRef>>)
            }
        }
        g.noteProject(project)
    }

    Page(project.project.name, onBack = { nav.pop() }, actions = {
        IconBtn(R.drawable.ic_add, "New chat") {
            val st = g.storage() ?: return@IconBtn
            nav.push(Screen.Chat(g.newProjectChat(st.second, project).key))
        }
        IconBtn(R.drawable.ic_settings, "Project settings") { nav.push(Screen.ProjectSettings(project)) }
    }) { pad ->
        ScrollColumn(pad) {
            message?.let { ErrorText(it) }
            when (val c = chats) {
                null -> Hint(if (g.storage() == null) "No project folder." else "Reading …")
                is StoreResult.Ok -> {
                    if (c.value.items.isEmpty()) Hint("No chats yet. Tap + to start one.")
                    c.value.items.sortedByDescending { it.chat.createdAt }.forEach { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).focusClickable(enabled = !busy) {
                                    g.existingFor(ref)?.let { nav.push(Screen.Chat(it.key)); return@focusClickable }
                                    busy = true
                                    scope.launch {
                                        val st = g.storage()
                                        val r = st?.let { withContext(Dispatchers.IO) { it.second.openChat(ref) } }
                                        busy = false
                                        when (r) {
                                            is StoreResult.Ok -> { message = null; nav.push(Screen.Chat(g.openProjectChat(st.second, r.value).key)) }
                                            null -> message = "No project folder."
                                            else -> message = "Chat cannot be opened: ${Texts.store(r)}"
                                        }
                                    }
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(ref.chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Hint(ref.chat.createdAt.take(16).replace('T', ' ') +
                                    if (g.existingFor(ref)?.job?.isActive == true) " · answer running" else "")
                            }
                            IconBtn(R.drawable.ic_delete, "Delete chat", tint = MaterialTheme.colorScheme.onSurfaceVariant) { toDelete = ref }
                        }
                        HorizontalDivider()
                    }
                    c.value.problems.forEach { ErrorText("Skipped: ${it.path} – ${it.detail}") }
                }
                else -> ErrorText(Texts.store(c))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                IconBtn(R.drawable.ic_refresh, "Reload") { reload++ }
            }
        }
    }

    toDelete?.let { ref ->
        val running = g.existingFor(ref)?.job?.isActive == true
        Confirm(
            title = "Delete chat?",
            text = if (running) "An answer is still running for this chat. Stop it in the chat first."
            else "\"${ref.chat.title}\" is permanently deleted with all files in its chat folder.",
            confirm = if (running) "OK" else "Delete",
            onConfirm = {
                toDelete = null
                if (!running) scope.launch {
                    val r = withContext(Dispatchers.IO) { g.storage()?.second?.deleteChat(ref) }
                    if (r is StoreResult.Ok) { g.forget(ref); message = null } else if (r != null) message = "Not deleted: ${Texts.store(r)}"
                    reload++
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}
