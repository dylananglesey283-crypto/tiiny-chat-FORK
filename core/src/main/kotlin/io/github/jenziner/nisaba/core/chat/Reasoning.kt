package io.github.jenziner.nisaba.core.chat

import io.github.jenziner.nisaba.core.api.ThinkingInfo
import io.github.jenziner.nisaba.core.api.ThinkingParams
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.ReasoningSetting

/** Was tatsächlich gesendet wird, und ein Hinweis, falls die Einstellung nicht (ganz) umsetzbar ist. */
data class ReasoningResolution(val params: ThinkingParams?, val note: String?)

/**
 * Bildet die Reasoning-Einstellung auf Anfrageparameter ab. Gesendet wird nur,
 * was das Modell laut `GET /models` unterstützt; sonst bleibt das Feld weg
 * und ein Hinweis erklärt den Grund. Es wird nie geraten.
 */
object ReasoningResolver {
    fun resolve(setting: ReasoningSetting, info: ThinkingInfo?): ReasoningResolution {
        if (setting.mode == ReasoningMode.MODEL_DEFAULT) return ReasoningResolution(null, null)
        if (info == null) return ReasoningResolution(null, "The model does not report its reasoning capabilities. Nothing is sent.")
        if (info.supported == false) return ReasoningResolution(null, "The model reports no reasoning support. Nothing is sent.")
        return when (setting.mode) {
            ReasoningMode.OFF -> if (info.toggleable == true) {
                ReasoningResolution(ThinkingParams(enabled = false, effort = null), null)
            } else {
                ReasoningResolution(null, "The model cannot switch reasoning off.")
            }
            ReasoningMode.ON -> {
                val enabled = if (info.toggleable == true) true else null
                val effort = setting.effort?.takeIf { it in info.levels }
                val notes = buildList {
                    if (setting.effort != null && effort == null) add("Depth \"${setting.effort}\" is not offered by the model.")
                    if (enabled == null && info.supported != true) add("The model cannot switch reasoning on.")
                }
                val params = if (enabled == null && effort == null) null else ThinkingParams(enabled, effort)
                ReasoningResolution(params, notes.joinToString(" ").ifEmpty { null })
            }
            ReasoningMode.MODEL_DEFAULT -> ReasoningResolution(null, null)
        }
    }
}
