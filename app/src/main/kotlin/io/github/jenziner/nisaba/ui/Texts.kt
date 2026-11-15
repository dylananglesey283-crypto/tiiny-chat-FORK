package io.github.jenziner.nisaba.ui

import io.github.jenziner.nisaba.ModelStatus
import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.chat.PersistState
import io.github.jenziner.nisaba.core.chat.SessionError
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.ReasoningSetting
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.store.StoreStatus

/** Texte der Oberfläche (Englisch, ab 0.3). */
object Texts {
    const val VAULT_NOTE =
        "Note: according to a third-party source, Tiiny additionally stores all chats in its own vault. " +
            "This is not verified on your device and cannot be prevented by this app."

    const val SOUL_NOTE =
        "Changes apply to new chats. Existing chats keep their previous project instructions."


    fun api(e: ApiError): String = when (e) {
        is ApiError.Connection -> "No connection to the model server: ${e.detail}"
        is ApiError.Timeout -> "Model server timeout: ${e.detail}"
        is ApiError.Auth -> "API key missing or wrong (HTTP ${e.code})."
        is ApiError.Gateway -> "Model server reports HTTP ${e.code}. After a restart, the Tiiny must be unlocked first."
        is ApiError.ModelUnavailable -> "Model not available: ${e.detail}"
        is ApiError.ContextLimit -> "Context too long: ${e.detail}"
        is ApiError.Server -> "Model server error (HTTP ${e.code}): ${e.detail}"
        is ApiError.Protocol -> "Unexpected answer: ${e.detail}"
    }

    fun storage(e: StorageError): String = when (e) {
        StorageError.Unavailable -> "Project storage not reachable"
        StorageError.PermissionDenied -> "Access to the project folder denied"
        StorageError.NotFound -> "not found"
        StorageError.AlreadyExists -> "already exists"
        is StorageError.NameChanged -> "Name changed by the storage ('${e.requested}' → '${e.actual}')"
        is StorageError.Io -> "I/O error: ${e.message}"
    }

    fun store(r: StoreResult<*>): String = when (r) {
        is StoreResult.Ok -> "ok"
        is StoreResult.StorageFailure -> storage(r.error)
        is StoreResult.Invalid -> "File damaged or unknown (${r.path}): ${r.detail}. It will not be overwritten."
        is StoreResult.Conflict -> r.detail
        is StoreResult.WrongStore -> "A different project storage is connected."
        is StoreResult.IdentityMismatch -> "Identity mismatch: ${r.detail}"
        is StoreResult.Interrupted -> "Write interrupted: ${storage(r.error)}"
    }

    fun storeStatus(s: StoreStatus): String = when (s) {
        is StoreStatus.Ready -> "ready"
        is StoreStatus.NotInitialized -> if (s.isEmpty) "Folder is empty and not set up yet" else "Folder is not empty and not a project storage"
        is StoreStatus.Invalid -> "nisaba-store.json is damaged or unknown: ${s.detail}"
        is StoreStatus.Failure -> storage(s.error)
    }

    fun persist(p: PersistState, reachable: Boolean): String = when (p) {
        PersistState.Memory -> "Not saved"
        PersistState.Draft -> if (reachable) "New chat · created on first send" else "Storage not reachable"
        PersistState.Saved -> if (reachable) "Saved" else "Saved · storage offline"
        is PersistState.Pending -> "Not saved yet (${p.reason})"
        is PersistState.WrongStore -> "Not saved: different storage connected"
        is PersistState.Conflict -> "Not saved: conflict (${p.detail})"
    }

    fun session(e: SessionError): String = when (e) {
        is SessionError.Model -> api(e.error)
        is SessionError.Storage -> e.detail
        is SessionError.Search -> "Web search failed: ${e.detail}. No request was sent to the model."
        is SessionError.Documents -> e.detail
    }

    fun model(s: ModelStatus, selected: String?): String = when (s) {
        ModelStatus.Unknown -> "${selected ?: "No model"} · not checked"
        ModelStatus.Checking -> "${selected ?: "No model"} · checking …"
        is ModelStatus.Ok -> when {
            selected == null -> "Connected · no model selected"
            s.models.none { it.id == selected } -> "Connected · $selected not available"
            else -> "$selected · connected"
        }
        is ModelStatus.Err -> "${selected ?: "No model"} · ${api(s.error)}"
    }

    fun reasoning(r: ReasoningSetting): String = when (r.mode) {
        ReasoningMode.MODEL_DEFAULT -> "Model default"
        ReasoningMode.OFF -> "Off"
        ReasoningMode.ON -> "On" + (r.effort?.let { " · $it" } ?: "")
    }
}
