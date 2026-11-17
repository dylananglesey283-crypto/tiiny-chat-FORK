package io.github.jenziner.nisaba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import io.github.jenziner.nisaba.ui.LocalIsTv
import io.github.jenziner.nisaba.ui.TvSafePadding
import io.github.jenziner.nisaba.ui.isTelevision
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.ui.ChatScreen
import io.github.jenziner.nisaba.ui.DiagnosticsScreen
import io.github.jenziner.nisaba.ui.NisabaTheme
import io.github.jenziner.nisaba.ui.ProjectScreen
import io.github.jenziner.nisaba.ui.SettingsScreen
import io.github.jenziner.nisaba.ui.SoulScreen
import io.github.jenziner.nisaba.ui.HomeScreen
import io.github.jenziner.nisaba.ui.ProjectSettingsScreen

sealed interface Screen {
    /** Startansicht = Ad-hoc-Chat mit Menü. */
    data object Home : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
    data class Project(val ref: ProjectRef) : Screen
    data class ProjectSettings(val ref: ProjectRef) : Screen
    data class Soul(val ref: ProjectRef) : Screen
    data class Chat(val key: String) : Screen
}

/** Einfacher Navigationsstapel ohne Bibliothek. */
class Nav(private val stack: MutableList<Screen>) {
    val top: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    fun popToRoot() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tv = isTelevision(this)
        setContent {
            CompositionLocalProvider(LocalIsTv provides tv) { NisabaTheme { Root() } }
        }
    }

    // Verbindungsüberwachung nur, solange die App sichtbar ist.
    override fun onStart() { super.onStart(); graph.startMonitor() }
    override fun onStop() { graph.stopMonitor(); super.onStop() }
}

@Composable
private fun Root() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Nav(stack) }
    Box(if (LocalIsTv.current) Modifier.padding(TvSafePadding) else Modifier) {
    when (val s = nav.top) {
        Screen.Home -> HomeScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        Screen.Diagnostics -> DiagnosticsScreen(nav)
        is Screen.Project -> ProjectScreen(nav, s.ref)
        is Screen.ProjectSettings -> ProjectSettingsScreen(nav, s.ref)
        is Screen.Soul -> SoulScreen(nav, s.ref)
        is Screen.Chat -> ChatScreen(nav, s.key)
    }
    }
    // Der SOUL-Editor behandelt Zurück selbst (Rückfrage bei ungespeicherten Änderungen).
    if (nav.top !is Screen.Soul) {
        BackHandler(enabled = nav.canPop) { nav.pop() }
    }
}
