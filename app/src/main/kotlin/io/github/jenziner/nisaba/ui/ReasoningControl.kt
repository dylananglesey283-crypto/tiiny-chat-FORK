package io.github.jenziner.nisaba.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.core.api.ThinkingInfo
import io.github.jenziner.nisaba.core.chat.ReasoningResolver
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.ReasoningSetting

/**
 * Reasoning-Wahl (Modell-Standard / Aus / Ein, optional Tiefe). Gesendet wird
 * nur, was das Modell laut Modellliste unterstützt; der Hinweis erklärt Abweichungen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasoningControl(value: ReasoningSetting, info: ThinkingInfo?, onChange: (ReasoningSetting) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(R.drawable.ic_psychology, null)
        Text("Reasoning")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ReasoningMode.MODEL_DEFAULT to "Model default", ReasoningMode.OFF to "Off", ReasoningMode.ON to "On").forEach { (m, label) ->
            FilterChip(selected = value.mode == m, onClick = { onChange(value.copy(mode = m)) }, label = { Text(label) })
        }
    }
    if (value.mode == ReasoningMode.ON) {
        val levels = (info?.levels.orEmpty() + listOfNotNull(value.effort)).distinct()
        if (levels.isNotEmpty()) {
            Hint("Depth")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = value.effort == null, onClick = { onChange(value.copy(effort = null)) }, label = { Text("Auto") })
                levels.forEach { l ->
                    FilterChip(selected = value.effort == l, onClick = { onChange(value.copy(effort = l)) }, label = { Text(l) })
                }
            }
        }
    }
    Hint(capabilities(info))
    ReasoningResolver.resolve(value, info).note?.let { ErrorText(it) }
}

fun capabilities(info: ThinkingInfo?): String = when {
    info == null -> "Model capabilities unknown (not reported or not connected). Only \"Model default\" is safe."
    info.supported == false -> "Model reports: no reasoning."
    else -> buildString {
        append("Model reports: reasoning")
        append(if (info.toggleable == true) ", switchable" else ", not switchable")
        info.enabledByDefault?.let { append(if (it) ", on by default" else ", off by default") }
        if (info.levels.isNotEmpty()) append(", depth ${info.levels.joinToString("/")}")
        info.defaultLevel?.let { append(" (default $it)") }
        append('.')
    }
}

@Composable
fun SwitchRow(icon: Int, title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(icon, null)
        Column(Modifier.weight(1f)) {
            Text(title)
            hint?.let { Hint(it) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
