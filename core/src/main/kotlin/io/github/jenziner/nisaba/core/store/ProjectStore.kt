package io.github.jenziner.nisaba.core.store

import io.github.jenziner.nisaba.core.Env
import io.github.jenziner.nisaba.core.FolderNames
import io.github.jenziner.nisaba.core.Titles
import io.github.jenziner.nisaba.core.format.ChatCodec
import io.github.jenziner.nisaba.core.format.ChatFile
import io.github.jenziner.nisaba.core.format.ChatOrigin
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.format.Codec
import io.github.jenziner.nisaba.core.format.Decoded
import io.github.jenziner.nisaba.core.format.FileNames
import io.github.jenziner.nisaba.core.format.HistoryFile
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.core.format.PROJECT_FORMAT_VERSION
import io.github.jenziner.nisaba.core.format.ProjectCodec
import io.github.jenziner.nisaba.core.format.ProjectFile
import io.github.jenziner.nisaba.core.format.ProjectSettings
import io.github.jenziner.nisaba.core.format.SoulSnapshotFile
import io.github.jenziner.nisaba.core.format.SoulState
import io.github.jenziner.nisaba.core.format.StoreFile
import io.github.jenziner.nisaba.core.format.TitleSource
import io.github.jenziner.nisaba.core.format.decodeUtf8Strict
import io.github.jenziner.nisaba.core.storage.ReadOutcome
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.storage.SafeReplace
import io.github.jenziner.nisaba.core.storage.StorageBackend
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.storage.WriteOutcome
import io.github.jenziner.nisaba.core.storage.sha256Hex
import io.github.jenziner.nisaba.core.timestamp
import kotlinx.serialization.KSerializer

/** Ergebnis aller Speicheroperationen; Fehlerarten bleiben unterscheidbar. */
sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>

    /** Speicher nicht erreichbar, Berechtigung fehlt oder E/A-Fehler. */
    data class StorageFailure(val error: StorageError) : StoreResult<Nothing>

    /** Datei beschädigt, unbekannt oder neuere Formatversion. Wird nicht überschrieben. */
    data class Invalid(val path: RelPath, val detail: String) : StoreResult<Nothing>

    /** Datei wurde ausserhalb dieser Sitzung verändert. */
    data class Conflict(val detail: String) : StoreResult<Nothing>

    /** Angeschlossen ist ein anderer Projekt-Speicher als der ursprüngliche. */
    data class WrongStore(val expected: String, val found: String?) : StoreResult<Nothing>

    /** Projekt- oder Chat-Identität stimmt nicht. */
    data class IdentityMismatch(val detail: String) : StoreResult<Nothing>

    /** Schreiben nach dem Beiseitelegen der alten Fassung unterbrochen. */
    data class Interrupted(val error: StorageError) : StoreResult<Nothing>
}

sealed interface StoreStatus {
    data class Ready(val storeId: String) : StoreStatus

    /** Keine Kennungsdatei vorhanden; `isEmpty` zeigt, ob der Ordner leer ist. */
    data class NotInitialized(val isEmpty: Boolean) : StoreStatus
    data class Invalid(val detail: String) : StoreStatus
    data class Failure(val error: StorageError) : StoreStatus
}

data class ProjectRef(
    val storeId: String,
    val dir: RelPath,
    val project: ProjectFile,
    /** SHA-256 der gelesenen project.json; Grundlage für sicheres Ersetzen. */
    val fileSha: String,
    /** Formatversion auf dem Speicher (1 = noch nicht migriert). */
    val sourceVersion: Int,
)

/** Ergebnis der Prüfung vor dem Löschen eines Projekts. */
sealed interface DeletionPlan {
    data class Allowed(val chatCount: Int) : DeletionPlan

    /** Löschen gesperrt; [reasons] nennt die betroffenen Einträge. */
    data class Blocked(val reasons: List<String>) : DeletionPlan
}

data class ChatRef(
    val project: ProjectRef,
    val dir: RelPath,
    val chat: ChatFile,
    val chatFileSha: String,
)

data class Problem(val path: RelPath, val detail: String)

/** Ein Dokument in `files/` eines Projekts. [size] in Bytes, sofern bekannt. */
data class DocumentRef(val project: ProjectRef, val name: String, val size: Long?)

object DocumentNames {
    const val MAX_LENGTH = 120

    /** Sicherer Dateiname: keine Pfadteile, Steuerzeichen oder führenden Punkte. */
    fun sanitize(name: String): String {
        val last = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = last.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.').trim()
        val fallback = clean.ifEmpty { "document" }
        if (fallback.length <= MAX_LENGTH) return fallback
        val ext = fallback.substringAfterLast('.', "").take(10)
        return if (ext.isEmpty()) fallback.take(MAX_LENGTH) else fallback.take(MAX_LENGTH - ext.length - 1) + "." + ext
    }

    /** `bericht.pdf` → `bericht (2).pdf`. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}

data class Listing<T>(val items: List<T>, val problems: List<Problem>)

/** Ergebnis beim Lesen von SOUL.md. Ein Lesefehler ist keine fehlende Datei. */
sealed interface SoulRead {
    data class Present(val content: String, val sha: String) : SoulRead
    data class Empty(val content: String, val sha: String) : SoulRead
    data object Missing : SoulRead
    data class Error(val detail: String, val storageError: StorageError?) : SoulRead
}

/** Alles, was eine laufende Chat-Sitzung zum Weiterspeichern braucht. */
data class ChatHandle(
    val storeId: String,
    val projectId: String,
    val projectDir: RelPath,
    val chatId: String,
    val chatDir: RelPath,
    val historyRevision: Long,
    val historySha: String,
)

data class OpenedChat(
    val ref: ChatRef,
    val handle: ChatHandle,
    val snapshot: SoulSnapshotFile,
    val messages: List<Message>,
    /** true, wenn ein unterbrochener Schreibvorgang beim Öffnen abgeschlossen wurde. */
    val recovered: Boolean,
)

class ProjectStore(
    private val storage: StorageBackend,
    private val env: Env,
) {
    private val safe = SafeReplace(storage) { env.newId().replace("-", "").take(8) }
    private val storePath = RelPath.of(FileNames.STORE)

    // ---------------------------------------------------------------- Speicher

    fun openStore(): StoreStatus {
        when (val r = storage.checkRoot()) {
            is SResult.Err -> return StoreStatus.Failure(r.error)
            is SResult.Ok -> Unit
        }
        return when (val r = safe.read(storePath)) {
            is ReadOutcome.Failed -> StoreStatus.Failure(r.error)
            is ReadOutcome.Inconsistent -> StoreStatus.Invalid(r.detail)
            ReadOutcome.Missing -> when (val l = storage.list(RelPath.ROOT)) {
                is SResult.Err -> StoreStatus.Failure(l.error)
                is SResult.Ok -> StoreStatus.NotInitialized(isEmpty = l.value.isEmpty())
            }
            is ReadOutcome.Found -> when (val d = Codec.decode(StoreFile.serializer(), StoreFile.KIND, r.bytes)) {
                is Decoded.Ok -> StoreStatus.Ready(d.value.storeId)
                else -> StoreStatus.Invalid(describe(storePath, d))
            }
        }
    }

    /** Legt die Kennungsdatei an. Eine vorhandene wird nie ersetzt. */
    fun initializeStore(): StoreStatus {
        val file = StoreFile(storeId = env.newId(), createdAt = env.timestamp())
        return when (val w = safe.createNew(storePath, Codec.encode(StoreFile.serializer(), file))) {
            WriteOutcome.Written -> StoreStatus.Ready(file.storeId)
            is WriteOutcome.Conflict -> openStore()
            is WriteOutcome.Failed -> StoreStatus.Failure(w.error)
            is WriteOutcome.Interrupted -> StoreStatus.Failure(w.error)
        }
    }

    /** Prüft, dass der angeschlossene Speicher derjenige mit [storeId] ist. */
    fun verifyStore(storeId: String): StoreResult<Unit> = when (val s = openStore()) {
        is StoreStatus.Ready -> if (s.storeId == storeId) StoreResult.Ok(Unit) else StoreResult.WrongStore(storeId, s.storeId)
        is StoreStatus.NotInitialized -> StoreResult.WrongStore(storeId, null)
        is StoreStatus.Invalid -> StoreResult.Invalid(storePath, s.detail)
        is StoreStatus.Failure -> StoreResult.StorageFailure(s.error)
    }

    // ---------------------------------------------------------------- Projekte

    fun listProjects(storeId: String): StoreResult<Listing<ProjectRef>> {
        verifyStore(storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val entries = when (val l = storage.list(RelPath.ROOT)) {
            is SResult.Err -> return StoreResult.StorageFailure(l.error)
            is SResult.Ok -> l.value
        }
        val items = mutableListOf<ProjectRef>()
        val problems = mutableListOf<Problem>()
        for (e in entries.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }) {
            val dir = RelPath.of(e.name)
            val path = dir.child(FileNames.PROJECT)
            when (val r = safe.read(path)) {
                ReadOutcome.Missing -> Unit // fremder Ordner, wird ignoriert
                is ReadOutcome.Failed -> return StoreResult.StorageFailure(r.error)
                is ReadOutcome.Inconsistent -> problems += Problem(path, r.detail)
                is ReadOutcome.Found -> when (val d = ProjectCodec.decode(r.bytes)) {
                    is Decoded.Ok -> items += ProjectRef(storeId, dir, d.value.file, sha256Hex(r.bytes), d.value.sourceVersion)
                    else -> problems += Problem(path, describe(path, d))
                }
            }
        }
        val dupes = items.groupBy { it.project.projectId }.filterValues { it.size > 1 }
        for ((id, refs) in dupes) {
            refs.forEach { problems += Problem(it.dir, "Project ID $id occurs more than once") }
        }
        return StoreResult.Ok(Listing(items.filter { it.project.projectId !in dupes }.sortedBy { it.project.name.lowercase() }, problems))
    }

    fun createProject(storeId: String, name: String): StoreResult<ProjectRef> {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        require(cleanName.isNotEmpty()) { "Project name missing" }
        verifyStore(storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val base = FolderNames.projectSlug(cleanName)
        var dir: RelPath? = null
        for (i in 1..50) {
            val candidate = RelPath.of(if (i == 1) base else "$base-$i")
            when (val r = storage.createDirectory(candidate)) {
                is SResult.Ok -> { dir = candidate; break }
                is SResult.Err -> if (r.error != StorageError.AlreadyExists) return StoreResult.StorageFailure(r.error)
            }
        }
        dir ?: return StoreResult.Conflict("No free folder name for $base")
        for (sub in listOf(FileNames.CHATS, FileNames.FILES)) {
            when (val r = storage.createDirectory(dir.child(sub))) {
                is SResult.Err -> if (r.error != StorageError.AlreadyExists) return StoreResult.StorageFailure(r.error)
                is SResult.Ok -> Unit
            }
        }
        val file = ProjectFile(projectId = env.newId(), name = cleanName, createdAt = env.timestamp(), settings = ProjectSettings.DEFAULT)
        val bytes = ProjectCodec.encode(file)
        return when (val w = safe.createNew(dir.child(FileNames.PROJECT), bytes)) {
            WriteOutcome.Written -> StoreResult.Ok(ProjectRef(storeId, dir, file, sha256Hex(bytes), PROJECT_FORMAT_VERSION))
            else -> w.toFailure()
        }
    }

    /** Liest project.json des Projekts neu (z. B. für aktuelle Einstellungen). */
    fun reloadProject(ref: ProjectRef): StoreResult<ProjectRef> {
        verifyStore(ref.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val path = ref.dir.child(FileNames.PROJECT)
        return when (val r = safe.read(path)) {
            ReadOutcome.Missing -> StoreResult.Invalid(path, "project.json is missing")
            is ReadOutcome.Failed -> StoreResult.StorageFailure(r.error)
            is ReadOutcome.Inconsistent -> StoreResult.Invalid(path, r.detail)
            is ReadOutcome.Found -> when (val d = ProjectCodec.decode(r.bytes)) {
                is Decoded.Ok -> if (d.value.file.projectId != ref.project.projectId) {
                    StoreResult.IdentityMismatch("Project ID in ${ref.dir} has changed")
                } else {
                    StoreResult.Ok(ref.copy(project = d.value.file, fileSha = sha256Hex(r.bytes), sourceVersion = d.value.sourceVersion))
                }
                else -> StoreResult.Invalid(path, describe(path, d))
            }
        }
    }

    /**
     * Speichert die Projekt-Einstellungen. Schreibt immer Formatversion 2;
     * eine Datei in Version 1 wird dabei einmalig migriert. Wurde project.json
     * seit dem Lesen verändert, wird nicht gespeichert (Konflikt).
     */
    fun updateProjectSettings(ref: ProjectRef, settings: ProjectSettings): StoreResult<ProjectRef> {
        val fresh = when (val r = reloadProject(ref)) {
            is StoreResult.Ok -> r.value
            else -> return r
        }
        if (fresh.fileSha != ref.fileSha) return StoreResult.Conflict("project.json was changed in the meantime. Please reopen the project settings.")
        val updated = fresh.project.copy(formatVersion = PROJECT_FORMAT_VERSION, settings = settings)
        val bytes = ProjectCodec.encode(updated)
        return when (val w = safe.replace(ref.dir.child(FileNames.PROJECT), bytes, ref.fileSha)) {
            WriteOutcome.Written -> StoreResult.Ok(fresh.copy(project = updated, fileSha = sha256Hex(bytes), sourceVersion = PROJECT_FORMAT_VERSION))
            else -> w.toFailure()
        }
    }

    /**
     * Prüft, ob ein Projekt gefahrlos gelöscht werden kann. Erlaubt sind nur
     * Einträge, die diese App selbst anlegt. Alles andere – insbesondere Dateien
     * in files/ oder skills/, deren Herkunft (z. B. Vault) die App nicht kennt –
     * sperrt das Löschen. So werden keine fremden oder geschützten Dateien mitgelöscht.
     */
    fun planProjectDeletion(ref: ProjectRef): StoreResult<DeletionPlan> {
        val fresh = when (val r = reloadProject(ref)) {
            is StoreResult.Ok -> r.value
            else -> return r.cast()
        }
        val reasons = mutableListOf<String>()
        val top = when (val l = storage.list(fresh.dir)) {
            is SResult.Err -> return StoreResult.StorageFailure(l.error)
            is SResult.Ok -> l.value
        }
        var chatCount = 0
        for (e in top) {
            when {
                !e.isDirectory && isOwnFile(e.name, FileNames.PROJECT, FileNames.SOUL) -> Unit
                e.isDirectory && (e.name == FileNames.FILES || e.name == FileNames.SKILLS) -> {
                    when (val l = storage.list(fresh.dir.child(e.name))) {
                        is SResult.Err -> return StoreResult.StorageFailure(l.error)
                        is SResult.Ok -> if (l.value.isNotEmpty()) {
                            reasons += if (e.name == FileNames.FILES) {
                                "The project still contains ${l.value.size} document(s). Delete all documents first (Project settings → Documents)."
                            } else {
                                "${e.name}/ contains ${l.value.size} entries"
                            }
                        }
                    }
                }
                e.isDirectory && e.name == FileNames.CHATS -> {
                    val chatsDir = fresh.dir.child(FileNames.CHATS)
                    val chats = when (val l = storage.list(chatsDir)) {
                        is SResult.Err -> return StoreResult.StorageFailure(l.error)
                        is SResult.Ok -> l.value
                    }
                    for (c in chats) {
                        if (!c.isDirectory) { reasons += "chats/${c.name} is not a chat folder"; continue }
                        val dir = chatsDir.child(c.name)
                        when (val r = readChatFile(fresh, dir)) {
                            is StoreResult.Ok -> Unit
                            is StoreResult.StorageFailure -> return r.cast()
                            else -> { reasons += "chats/${c.name}: unreadable chat"; continue }
                        }
                        val inner = when (val l = storage.list(dir)) {
                            is SResult.Err -> return StoreResult.StorageFailure(l.error)
                            is SResult.Ok -> l.value
                        }
                        for (i in inner) {
                            val ok = if (i.isDirectory) {
                                i.name == FileNames.ATTACHMENTS &&
                                    (storage.list(dir.child(i.name)) as? SResult.Ok)?.value?.isEmpty() == true
                            } else {
                                isOwnFile(i.name, FileNames.CHAT, FileNames.HISTORY, FileNames.SNAPSHOT)
                            }
                            if (!ok) reasons += "chats/${c.name}/${i.name} was not created by this app"
                        }
                        chatCount++
                    }
                }
                else -> reasons += "${e.name}${if (e.isDirectory) "/" else ""} was not created by this app"
            }
        }
        return StoreResult.Ok(if (reasons.isEmpty()) DeletionPlan.Allowed(chatCount) else DeletionPlan.Blocked(reasons))
    }

    /**
     * Löscht ein Projekt endgültig mit allen Chats. Vorher wird die Prüfung
     * [planProjectDeletion] wiederholt; ist sie nicht mehr erfüllt, wird nichts gelöscht.
     */
    fun deleteProject(ref: ProjectRef): StoreResult<Unit> {
        when (val p = planProjectDeletion(ref)) {
            is StoreResult.Ok -> when (val plan = p.value) {
                is DeletionPlan.Blocked -> return StoreResult.Conflict("Deletion blocked: " + plan.reasons.joinToString("; "))
                is DeletionPlan.Allowed -> Unit
            }
            else -> return p.cast()
        }
        return when (val d = storage.delete(ref.dir)) {
            is SResult.Ok -> StoreResult.Ok(Unit)
            is SResult.Err -> StoreResult.StorageFailure(d.error)
        }
    }

    /** Eigene Datei oder Zwischendatei des sicheren Ersetzens (`name.tmp-*`, `name.bak-*`). */
    private fun isOwnFile(name: String, vararg own: String): Boolean =
        own.any { name == it || name.startsWith("$it.tmp-") || name.startsWith("$it.bak-") }

    // ---------------------------------------------------------------- SOUL.md

    fun readSoul(project: ProjectRef): SoulRead {
        val path = project.dir.child(FileNames.SOUL)
        // Über SafeReplace gelesen: Ein unterbrochenes Ersetzen (SOUL.md beiseitegelegt,
        // neue Fassung noch als tmp) darf nicht als "fehlt" erscheinen.
        return when (val r = safe.read(path)) {
            ReadOutcome.Missing -> SoulRead.Missing
            is ReadOutcome.Failed -> SoulRead.Error("SOUL.md could not be read", r.error)
            is ReadOutcome.Inconsistent -> SoulRead.Error("SOUL.md: unklare Zwischendateien (${r.detail})", null)
            is ReadOutcome.Found -> {
                val text = decodeUtf8Strict(r.bytes)
                    ?: return SoulRead.Error("SOUL.md is not valid UTF-8", null)
                val sha = sha256Hex(r.bytes)
                if (text.isBlank()) SoulRead.Empty(text, sha) else SoulRead.Present(text, sha)
            }
        }
    }

    /**
     * Speichert SOUL.md. [loaded] ist der Stand, den der Editor geöffnet hat;
     * hat sich die Datei seither verändert, wird nicht gespeichert.
     */
    fun writeSoul(project: ProjectRef, loaded: SoulRead, content: String): StoreResult<SoulRead> {
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val path = project.dir.child(FileNames.SOUL)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val outcome = when (loaded) {
            SoulRead.Missing -> safe.createNew(path, bytes)
            is SoulRead.Present -> safe.replace(path, bytes, loaded.sha)
            is SoulRead.Empty -> safe.replace(path, bytes, loaded.sha)
            is SoulRead.Error -> return StoreResult.Conflict("SOUL.md was not read without errors; saving is blocked")
        }
        return when (outcome) {
            WriteOutcome.Written -> StoreResult.Ok(readSoul(project))
            else -> outcome.toFailure()
        }
    }

    // ---------------------------------------------------------------- Chats

    fun listChats(project: ProjectRef): StoreResult<Listing<ChatRef>> {
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val chatsDir = project.dir.child(FileNames.CHATS)
        val entries = when (val l = storage.list(chatsDir)) {
            is SResult.Err -> return if (l.error == StorageError.NotFound) {
                StoreResult.Ok(Listing(emptyList(), emptyList()))
            } else {
                StoreResult.StorageFailure(l.error)
            }
            is SResult.Ok -> l.value
        }
        val items = mutableListOf<ChatRef>()
        val problems = mutableListOf<Problem>()
        for (e in entries.filter { it.isDirectory && !it.name.startsWith(".") }) {
            val dir = chatsDir.child(e.name)
            when (val r = readChatFile(project, dir)) {
                is StoreResult.Ok -> items += r.value
                is StoreResult.StorageFailure -> return r
                is StoreResult.Invalid -> problems += Problem(r.path, r.detail)
                else -> problems += Problem(dir, r.toString())
            }
        }
        return StoreResult.Ok(Listing(items.sortedByDescending { it.chat.createdAt }, problems))
    }

    private fun readChatFile(project: ProjectRef, dir: RelPath): StoreResult<ChatRef> {
        val path = dir.child(FileNames.CHAT)
        return when (val r = safe.read(path)) {
            ReadOutcome.Missing -> StoreResult.Invalid(path, "chat.json is missing")
            is ReadOutcome.Failed -> StoreResult.StorageFailure(r.error)
            is ReadOutcome.Inconsistent -> StoreResult.Invalid(path, r.detail)
            is ReadOutcome.Found -> when (val d = ChatCodec.decode(r.bytes)) {
                is Decoded.Ok -> if (d.value.file.projectId != project.project.projectId) {
                    StoreResult.Invalid(path, "Chat belongs to a different project")
                } else {
                    StoreResult.Ok(ChatRef(project, dir, d.value.file, sha256Hex(r.bytes)))
                }
                else -> StoreResult.Invalid(path, describe(path, d))
            }
        }
    }

    sealed interface BeginResult {
        data class Started(val opened: OpenedChat) : BeginResult

        /** SOUL.md konnte nicht gelesen werden; es wurde nichts angelegt. */
        data class SoulUnreadable(val soul: SoulRead.Error) : BeginResult
        data class Failed(val failure: StoreResult<Nothing>) : BeginResult
    }

    /**
     * Erstes Senden in einem neuen Projekt-Chat: liest SOUL.md, legt den
     * Chat-Ordner an und schreibt Snapshot, chat.json und history.json mit
     * der ersten Nutzernachricht. Erst wenn alles geschrieben und
     * zurückgelesen ist, darf die Modellanfrage folgen.
     * Bei einem Fehler wird der in diesem Vorgang angelegte Ordner nach
     * Möglichkeit wieder entfernt.
     */
    fun beginChat(project: ProjectRef, firstMessage: Message, documents: List<String> = emptyList()): BeginResult {
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return BeginResult.Failed(it.cast()) }
        val soul = readSoul(project)
        if (soul is SoulRead.Error) return BeginResult.SoulUnreadable(soul)
        return createChat(project, soul, listOf(firstMessage), ChatOrigin.PROJECT, documents)
    }

    /**
     * Speichert einen Ad-hoc-Chat als neuen Projekt-Chat. Der Chat bleibt ohne
     * Projektanweisungen (Snapshot-Zustand `missing`, Herkunft `ad-hoc`), weil
     * seine bisherigen Antworten ohne sie entstanden sind.
     */
    fun importAdHoc(project: ProjectRef, messages: List<Message>): BeginResult {
        require(messages.any { it.role == Role.USER }) { "Nothing to save" }
        require(messages.map { it.id }.toSet().size == messages.size) { "Doppelte Nachrichten-IDs" }
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return BeginResult.Failed(it.cast()) }
        return createChat(project, SoulRead.Missing, messages, ChatOrigin.AD_HOC, emptyList())
    }

    private fun createChat(
        project: ProjectRef,
        soul: SoulRead,
        messages: List<Message>,
        origin: ChatOrigin,
        documents: List<String>,
    ): BeginResult {
        val firstMessage = messages.first { it.role == Role.USER }
        val chatsDir = project.dir.child(FileNames.CHATS)
        when (val r = storage.createDirectory(chatsDir)) {
            is SResult.Err -> if (r.error != StorageError.AlreadyExists) return BeginResult.Failed(StoreResult.StorageFailure(r.error))
            is SResult.Ok -> Unit
        }
        val chatId = env.newId()
        var dir: RelPath? = null
        for (i in 0 until 10) {
            val candidate = chatsDir.child(FolderNames.chatFolder(env, env.newId().replace("-", "").take(4)))
            when (val r = storage.createDirectory(candidate)) {
                is SResult.Ok -> { dir = candidate; break }
                is SResult.Err -> if (r.error != StorageError.AlreadyExists) return BeginResult.Failed(StoreResult.StorageFailure(r.error))
            }
        }
        dir ?: return BeginResult.Failed(StoreResult.Conflict("No free chat folder name"))

        val snapshot = SoulSnapshotFile(
            chatId = chatId,
            takenAt = env.timestamp(),
            state = when (soul) {
                is SoulRead.Present -> SoulState.PRESENT
                is SoulRead.Empty -> SoulState.EMPTY
                else -> SoulState.MISSING
            },
            content = when (soul) {
                is SoulRead.Present -> soul.content
                is SoulRead.Empty -> soul.content
                else -> null
            },
        )
        val chat = ChatFile(
            chatId = chatId,
            projectId = project.project.projectId,
            title = Titles.fromFirstMessage(firstMessage.content),
            titleSource = TitleSource.AUTO,
            createdAt = env.timestamp(),
            origin = origin,
            documents = documents.distinct(),
        )
        val history = HistoryFile(chatId = chatId, revision = 1, messages = messages)
        val historyBytes = Codec.encode(HistoryFile.serializer(), history)
        val chatBytes = ChatCodec.encode(chat)

        val steps = listOf(
            dir.child(FileNames.SNAPSHOT) to Codec.encode(SoulSnapshotFile.serializer(), snapshot),
            dir.child(FileNames.CHAT) to chatBytes,
            dir.child(FileNames.HISTORY) to historyBytes,
        )
        for ((path, bytes) in steps) {
            val w = safe.createNew(path, bytes)
            if (w != WriteOutcome.Written) {
                storage.delete(dir)
                return BeginResult.Failed(w.toFailure())
            }
        }
        val ref = ChatRef(project, dir, chat, sha256Hex(chatBytes))
        val handle = ChatHandle(
            storeId = project.storeId,
            projectId = project.project.projectId,
            projectDir = project.dir,
            chatId = chatId,
            chatDir = dir,
            historyRevision = 1,
            historySha = sha256Hex(historyBytes),
        )
        return BeginResult.Started(OpenedChat(ref, handle, snapshot, history.messages, recovered = false))
    }

    fun openChat(ref: ChatRef): StoreResult<OpenedChat> {
        verifyStore(ref.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val fresh = when (val r = readChatFile(ref.project, ref.dir)) {
            is StoreResult.Ok -> r.value
            else -> return r.cast()
        }
        if (fresh.chat.chatId != ref.chat.chatId) return StoreResult.IdentityMismatch("Chat ID in folder ${ref.dir} has changed")
        val snapshot = when (val r = readJson(ref.dir.child(FileNames.SNAPSHOT), SoulSnapshotFile.serializer(), SoulSnapshotFile.KIND)) {
            is StoreResult.Ok -> r.value.first
            else -> return r.cast()
        }
        if (snapshot.chatId != fresh.chat.chatId) {
            return StoreResult.Invalid(ref.dir.child(FileNames.SNAPSHOT), "Snapshot belongs to a different chat")
        }
        val historyPath = ref.dir.child(FileNames.HISTORY)
        val (history, sha, recovered) = when (val r = readJson(historyPath, HistoryFile.serializer(), HistoryFile.KIND)) {
            is StoreResult.Ok -> r.value
            else -> return r.cast()
        }
        validateHistory(historyPath, history, fresh.chat.chatId)?.let { return it }
        val handle = ChatHandle(
            storeId = ref.project.storeId,
            projectId = ref.project.project.projectId,
            projectDir = ref.project.dir,
            chatId = history.chatId,
            chatDir = ref.dir,
            historyRevision = history.revision,
            historySha = sha,
        )
        return StoreResult.Ok(OpenedChat(fresh, handle, snapshot, history.messages, recovered))
    }

    /**
     * Schreibt den vollständigen Nachrichtenverlauf als neue Revision.
     *
     * Vorher geprüft: derselbe Speicher (storeId), dasselbe Projekt, derselbe
     * Chat und dass history.json noch genau dem zuletzt gespeicherten Stand
     * entspricht. Liegt auf dem Speicher bereits genau der Stand, der jetzt
     * geschrieben werden soll (z. B. weil das letzte Schreiben gelang, die
     * Bestätigung aber wegen Verbindungsabbruchs fehlte), gilt er als
     * gespeichert; es entstehen keine Duplikate.
     */
    fun saveHistory(handle: ChatHandle, messages: List<Message>): StoreResult<ChatHandle> {
        verifyStore(handle.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        verifyIdentity(handle).let { if (it !is StoreResult.Ok) return it.cast() }
        require(messages.map { it.id }.toSet().size == messages.size) { "Doppelte Nachrichten-IDs" }

        val historyPath = handle.chatDir.child(FileNames.HISTORY)
        val next = HistoryFile(chatId = handle.chatId, revision = handle.historyRevision + 1, messages = messages)
        val bytes = Codec.encode(HistoryFile.serializer(), next)
        return when (val w = safe.replace(historyPath, bytes, handle.historySha)) {
            WriteOutcome.Written -> StoreResult.Ok(handle.copy(historyRevision = next.revision, historySha = sha256Hex(bytes)))
            is WriteOutcome.Conflict -> {
                // Ist unser eigener letzter Schreibvorgang bereits angekommen?
                when (val r = readJson(historyPath, HistoryFile.serializer(), HistoryFile.KIND)) {
                    is StoreResult.Ok -> {
                        val (onDisk, sha, _) = r.value
                        if (onDisk.chatId == handle.chatId && onDisk.revision == next.revision && onDisk.messages == messages) {
                            StoreResult.Ok(handle.copy(historyRevision = onDisk.revision, historySha = sha))
                        } else {
                            StoreResult.Conflict(w.reason)
                        }
                    }
                    else -> StoreResult.Conflict(w.reason)
                }
            }
            else -> w.toFailure()
        }
    }

    fun renameChat(ref: ChatRef, newTitle: String): StoreResult<ChatRef> {
        val title = Titles.normalizeManual(newTitle) ?: return StoreResult.Conflict("Title must not be empty")
        verifyStore(ref.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        return replaceChatFile(ref, ref.chat.copy(title = title, titleSource = TitleSource.MANUAL))
    }

    /**
     * Speichert die Auswahl aktiver Dokumente eines Chats in chat.json
     * (dabei wird eine Datei in Version 1 einmalig auf Version 2 migriert).
     */
    fun updateChatDocuments(ref: ChatRef, documents: List<String>): StoreResult<ChatRef> {
        verifyStore(ref.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        return replaceChatFile(ref, ref.chat.copy(documents = documents.distinct()))
    }

    private fun replaceChatFile(ref: ChatRef, updated: ChatFile): StoreResult<ChatRef> {
        val file = updated.copy(formatVersion = io.github.jenziner.nisaba.core.format.CHAT_FORMAT_VERSION)
        val bytes = ChatCodec.encode(file)
        return when (val w = safe.replace(ref.dir.child(FileNames.CHAT), bytes, ref.chatFileSha)) {
            WriteOutcome.Written -> StoreResult.Ok(ref.copy(chat = file, chatFileSha = sha256Hex(bytes)))
            else -> w.toFailure()
        }
    }

    // ---------------------------------------------------------------- Dokumente

    /** Dokumente im Ordner `files/` des Projekts; Unterordner werden als Problem gemeldet. */
    fun listDocuments(project: ProjectRef): StoreResult<Listing<DocumentRef>> {
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val dir = project.dir.child(FileNames.FILES)
        val entries = when (val l = storage.list(dir)) {
            is SResult.Err -> return if (l.error == StorageError.NotFound) {
                StoreResult.Ok(Listing(emptyList(), emptyList()))
            } else {
                StoreResult.StorageFailure(l.error)
            }
            is SResult.Ok -> l.value
        }
        val items = mutableListOf<DocumentRef>()
        val problems = mutableListOf<Problem>()
        for (e in entries.sortedBy { it.name.lowercase() }) {
            when {
                e.isDirectory -> problems += Problem(dir.child(e.name), "Folder in files/ is not used as a document")
                else -> items += DocumentRef(project, e.name, e.size)
            }
        }
        return StoreResult.Ok(Listing(items, problems))
    }

    /**
     * Legt ein Dokument in `files/` an. Ein vorhandenes Dokument mit gleichem
     * Namen wird nie ersetzt; stattdessen erhält das neue einen Zusatz `(2)`, `(3)` …
     */
    fun addDocument(project: ProjectRef, requestedName: String, bytes: ByteArray): StoreResult<DocumentRef> {
        verifyStore(project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val dir = project.dir.child(FileNames.FILES)
        when (val r = storage.createDirectory(dir)) {
            is SResult.Err -> if (r.error != StorageError.AlreadyExists) return StoreResult.StorageFailure(r.error)
            is SResult.Ok -> Unit
        }
        val base = DocumentNames.sanitize(requestedName)
        for (i in 1..50) {
            val name = if (i == 1) base else DocumentNames.numbered(base, i)
            when (val w = safe.createNew(dir.child(name), bytes)) {
                WriteOutcome.Written -> return StoreResult.Ok(DocumentRef(project, name, bytes.size.toLong()))
                is WriteOutcome.Conflict -> continue
                else -> return w.toFailure()
            }
        }
        return StoreResult.Conflict("No free document name for $base")
    }

    fun readDocument(doc: DocumentRef): StoreResult<ByteArray> {
        verifyStore(doc.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        return when (val r = storage.read(doc.project.dir.child(FileNames.FILES).child(doc.name))) {
            is SResult.Ok -> StoreResult.Ok(r.value)
            is SResult.Err -> StoreResult.StorageFailure(r.error)
        }
    }

    /** Löscht ein einzelnes Dokument endgültig. Nur Dateien direkt in `files/`. */
    fun deleteDocument(doc: DocumentRef): StoreResult<Unit> {
        verifyStore(doc.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        val path = doc.project.dir.child(FileNames.FILES).child(doc.name)
        when (val st = storage.stat(path)) {
            is SResult.Err -> return StoreResult.StorageFailure(st.error)
            is SResult.Ok -> when {
                st.value == null -> return StoreResult.Ok(Unit)
                st.value.isDirectory -> return StoreResult.Conflict("${doc.name} is a folder, not a document")
                else -> Unit
            }
        }
        return when (val d = storage.delete(path)) {
            is SResult.Ok -> StoreResult.Ok(Unit)
            is SResult.Err -> StoreResult.StorageFailure(d.error)
        }
    }

    /** Löscht einen Chat-Ordner, aber nur, wenn er noch genau diesen Chat enthält. */
    fun deleteChat(ref: ChatRef): StoreResult<Unit> {
        verifyStore(ref.project.storeId).let { if (it !is StoreResult.Ok) return it.cast() }
        when (val r = readChatFile(ref.project, ref.dir)) {
            is StoreResult.Ok -> if (r.value.chat.chatId != ref.chat.chatId) {
                return StoreResult.IdentityMismatch("Folder contains a different chat")
            }
            else -> return r.cast()
        }
        return when (val d = storage.delete(ref.dir)) {
            is SResult.Ok -> StoreResult.Ok(Unit)
            is SResult.Err -> StoreResult.StorageFailure(d.error)
        }
    }

    // ---------------------------------------------------------------- intern

    private fun verifyIdentity(handle: ChatHandle): StoreResult<Unit> {
        val projectPath = handle.projectDir.child(FileNames.PROJECT)
        when (val r = safe.read(projectPath)) {
            ReadOutcome.Missing -> return StoreResult.Invalid(projectPath, "project.json is missing")
            is ReadOutcome.Failed -> return StoreResult.StorageFailure(r.error)
            is ReadOutcome.Inconsistent -> return StoreResult.Invalid(projectPath, r.detail)
            is ReadOutcome.Found -> when (val d = ProjectCodec.decode(r.bytes)) {
                is Decoded.Ok -> if (d.value.file.projectId != handle.projectId) {
                    return StoreResult.IdentityMismatch("Project ID in ${handle.projectDir} does not match")
                }
                else -> return StoreResult.Invalid(projectPath, describe(projectPath, d))
            }
        }
        val chatPath = handle.chatDir.child(FileNames.CHAT)
        when (val r = safe.read(chatPath)) {
            ReadOutcome.Missing -> return StoreResult.Invalid(chatPath, "chat.json is missing")
            is ReadOutcome.Failed -> return StoreResult.StorageFailure(r.error)
            is ReadOutcome.Inconsistent -> return StoreResult.Invalid(chatPath, r.detail)
            is ReadOutcome.Found -> {
                val chat = when (val d = ChatCodec.decode(r.bytes)) {
                    is Decoded.Ok -> d.value.file
                    else -> return StoreResult.Invalid(chatPath, describe(chatPath, d))
                }
                if (chat.chatId != handle.chatId || chat.projectId != handle.projectId) {
                    return StoreResult.IdentityMismatch("Chat ID in ${handle.chatDir} does not match")
                }
            }
        }
        return StoreResult.Ok(Unit)
    }

    private fun validateHistory(path: RelPath, h: HistoryFile, chatId: String): StoreResult<Nothing>? {
        if (h.chatId != chatId) return StoreResult.Invalid(path, "history.json belongs to a different chat")
        if (h.messages.map { it.id }.toSet().size != h.messages.size) return StoreResult.Invalid(path, "Doppelte Nachrichten-IDs")
        if (h.revision < 1) return StoreResult.Invalid(path, "Invalid revision")
        return null
    }

    private fun <T> readJson(path: RelPath, serializer: KSerializer<T>, kind: String): StoreResult<Triple<T, String, Boolean>> =
        when (val r = safe.read(path)) {
            ReadOutcome.Missing -> StoreResult.Invalid(path, "${path.name} fehlt")
            is ReadOutcome.Failed -> StoreResult.StorageFailure(r.error)
            is ReadOutcome.Inconsistent -> StoreResult.Invalid(path, r.detail)
            is ReadOutcome.Found -> when (val d = Codec.decode(serializer, kind, r.bytes)) {
                is Decoded.Ok -> StoreResult.Ok(Triple(d.value, sha256Hex(r.bytes), r.recovered))
                else -> StoreResult.Invalid(path, describe(path, d))
            }
        }

    private fun describe(path: RelPath, d: Decoded<*>): String = when (d) {
        is Decoded.Ok -> "ok"
        is Decoded.Corrupt -> "${path.name} is damaged: ${d.detail}"
        is Decoded.WrongKind -> "${path.name} has an unknown file kind: ${d.found}"
        is Decoded.UnsupportedVersion -> "${path.name} has an unsupported format version: ${d.found}"
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> StoreResult<*>.cast(): StoreResult<T> = this as StoreResult<T>

private fun WriteOutcome.toFailure(): StoreResult<Nothing> = when (this) {
    WriteOutcome.Written -> error("kein Fehler")
    is WriteOutcome.Conflict -> StoreResult.Conflict(reason)
    is WriteOutcome.Failed -> StoreResult.StorageFailure(error)
    is WriteOutcome.Interrupted -> StoreResult.Interrupted(error)
}
