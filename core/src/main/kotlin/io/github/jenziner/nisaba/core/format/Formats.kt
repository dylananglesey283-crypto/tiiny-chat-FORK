package io.github.jenziner.nisaba.core.format

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dateiformate im Projekt-Stammordner. Siehe docs/DATENFORMAT.md.
 *
 * Jede JSON-Datei trägt `format` (Dateiart) und `formatVersion`.
 * Unbekannte Felder, unbekannte Dateiarten und neuere Versionen werden
 * nicht gelesen und deshalb auch nie überschrieben.
 */
const val FORMAT_VERSION = 1

/**
 * project.json: Version 2 seit 0.3 (Projekt-Einstellungen), Version 3 seit 0.4
 * (Dokumentlimite). Versionen 1 und 2 werden weiterhin gelesen.
 */
const val PROJECT_FORMAT_VERSION = 3

/** chat.json: Version 2 seit 0.4 (Herkunft, aktive Dokumente). Version 1 wird weiterhin gelesen. */
const val CHAT_FORMAT_VERSION = 2

object FileNames {
    const val STORE = "nisaba-store.json"
    const val PROJECT = "project.json"
    const val SOUL = "SOUL.md"
    const val CHATS = "chats"
    const val FILES = "files"
    const val SKILLS = "skills"
    const val CHAT = "chat.json"
    const val HISTORY = "history.json"
    const val SNAPSHOT = "soul-snapshot.json"
    const val ATTACHMENTS = "attachments"
}

@Serializable
data class StoreFile(
    val format: String = KIND,
    val formatVersion: Int = FORMAT_VERSION,
    val storeId: String,
    val createdAt: String,
) {
    companion object { const val KIND = "nisaba-store" }
}

/** Reasoning-Einstellung. Siehe docs/DATENFORMAT.md. */
@Serializable
enum class ReasoningMode {
    /** Nichts senden; das Modell verhält sich nach seiner Voreinstellung. */
    @SerialName("model-default") MODEL_DEFAULT,

    /** `thinking_enabled: false`, nur wenn das Modell Umschalten meldet. */
    @SerialName("off") OFF,

    /** `thinking_enabled: true`, nur wenn das Modell Umschalten meldet. */
    @SerialName("on") ON,
}

@Serializable
data class ReasoningSetting(
    val mode: ReasoningMode,
    /** Gewünschte Denktiefe (`reasoning_effort`); wird nur gesendet, wenn das Modell diese Stufe meldet. */
    val effort: String?,
) {
    companion object { val DEFAULT = ReasoningSetting(ReasoningMode.MODEL_DEFAULT, null) }
}

@Serializable
data class ProjectSettings(
    val reasoning: ReasoningSetting,
    /** Websuche über die in der App eingestellte Suchmaschine. */
    val webSearch: Boolean,
    /**
     * Höchstmenge aktiver Dokumente pro Anfrage in geschätzten Tokens.
     * `null` = Wert aus den App-Einstellungen. Gilt nur, wenn das Modell
     * keine Kontextgrösse meldet; sonst gilt die halbe Kontextgrösse.
     */
    val documentLimitTokens: Int? = null,
) {
    companion object { val DEFAULT = ProjectSettings(ReasoningSetting.DEFAULT, webSearch = false, documentLimitTokens = null) }
}

/** Projekt-Einstellungen in project.json Version 2 (0.3); nur lesen. */
@Serializable
internal data class ProjectSettingsV2(
    val reasoning: ReasoningSetting,
    val webSearch: Boolean,
)

/** project.json, Formatversion 2 (0.3); nur lesen. */
@Serializable
internal data class ProjectFileV2(
    val format: String = ProjectFile.KIND,
    val formatVersion: Int = 2,
    val projectId: String,
    val name: String,
    val createdAt: String,
    val settings: ProjectSettingsV2,
)

/** project.json, Formatversion 3. */
@Serializable
data class ProjectFile(
    val format: String = KIND,
    val formatVersion: Int = PROJECT_FORMAT_VERSION,
    val projectId: String,
    val name: String,
    val createdAt: String,
    val settings: ProjectSettings,
) {
    companion object { const val KIND = "nisaba-project" }
}

/** project.json, Formatversion 1 (bis 0.2.x); nur lesen. */
@Serializable
internal data class ProjectFileV1(
    val format: String = ProjectFile.KIND,
    val formatVersion: Int = 1,
    val projectId: String,
    val name: String,
    val createdAt: String,
)

/** Gelesene project.json; [sourceVersion] = Formatversion auf dem Speicher. */
data class ProjectRead(val file: ProjectFile, val sourceVersion: Int)

object ProjectCodec {
    /**
     * Liest Version 3 streng. Versionen 1 und 2 werden streng gelesen und im
     * Arbeitsspeicher mit Standardwerten ergänzt. Geschrieben wird immer
     * Version 3, und zwar erst, wenn die Projekt-Einstellungen gespeichert werden.
     */
    fun decode(bytes: ByteArray): Decoded<ProjectRead> =
        when (val v3 = Codec.decode(ProjectFile.serializer(), ProjectFile.KIND, bytes, expected = PROJECT_FORMAT_VERSION)) {
            is Decoded.Ok -> Decoded.Ok(ProjectRead(v3.value, PROJECT_FORMAT_VERSION))
            is Decoded.UnsupportedVersion -> when (v3.found) {
                2 -> Codec.decode(ProjectFileV2.serializer(), ProjectFile.KIND, bytes, expected = 2).map {
                    ProjectRead(
                        ProjectFile(
                            projectId = it.projectId, name = it.name, createdAt = it.createdAt,
                            settings = ProjectSettings(it.settings.reasoning, it.settings.webSearch, documentLimitTokens = null),
                        ),
                        sourceVersion = 2,
                    )
                }
                1 -> Codec.decode(ProjectFileV1.serializer(), ProjectFile.KIND, bytes, expected = 1).map {
                    ProjectRead(
                        ProjectFile(projectId = it.projectId, name = it.name, createdAt = it.createdAt, settings = ProjectSettings.DEFAULT),
                        sourceVersion = 1,
                    )
                }
                else -> v3
            }
            is Decoded.Corrupt -> v3
            is Decoded.WrongKind -> v3
        }

    fun encode(file: ProjectFile): ByteArray = Codec.encode(ProjectFile.serializer(), file)
}

@Serializable
enum class TitleSource {
    @SerialName("auto") AUTO,
    @SerialName("manual") MANUAL,
}

/** Herkunft eines Projekt-Chats. */
@Serializable
enum class ChatOrigin {
    /** Im Projekt begonnen; Projektanweisungen als Snapshot beim ersten Senden. */
    @SerialName("project") PROJECT,

    /** Als Ad-hoc-Chat begonnen und später ins Projekt gespeichert; ohne Projektanweisungen. */
    @SerialName("ad-hoc") AD_HOC,
}

/** chat.json, Formatversion 2. */
@Serializable
data class ChatFile(
    val format: String = KIND,
    val formatVersion: Int = CHAT_FORMAT_VERSION,
    val chatId: String,
    val projectId: String,
    val title: String,
    val titleSource: TitleSource,
    val createdAt: String,
    val origin: ChatOrigin = ChatOrigin.PROJECT,
    /** Dateinamen in `files/` des Projekts, die für diesen Chat aktiv sind, in Auswahlreihenfolge. */
    val documents: List<String> = emptyList(),
) {
    companion object { const val KIND = "nisaba-chat" }
}

/** chat.json, Formatversion 1 (bis 0.3); nur lesen. */
@Serializable
internal data class ChatFileV1(
    val format: String = ChatFile.KIND,
    val formatVersion: Int = 1,
    val chatId: String,
    val projectId: String,
    val title: String,
    val titleSource: TitleSource,
    val createdAt: String,
)

data class ChatRead(val file: ChatFile, val sourceVersion: Int)

object ChatCodec {
    /**
     * Liest Version 2 streng; Version 1 wird streng gelesen und als Projekt-Chat
     * ohne aktive Dokumente ergänzt. Geschrieben wird immer Version 2, erst
     * wenn sich Titel oder Dokumentauswahl ändern.
     */
    fun decode(bytes: ByteArray): Decoded<ChatRead> =
        when (val v2 = Codec.decode(ChatFile.serializer(), ChatFile.KIND, bytes, expected = CHAT_FORMAT_VERSION)) {
            is Decoded.Ok -> Decoded.Ok(ChatRead(v2.value, CHAT_FORMAT_VERSION))
            is Decoded.UnsupportedVersion -> if (v2.found == 1) {
                Codec.decode(ChatFileV1.serializer(), ChatFile.KIND, bytes, expected = 1).map {
                    ChatRead(
                        ChatFile(
                            chatId = it.chatId, projectId = it.projectId, title = it.title,
                            titleSource = it.titleSource, createdAt = it.createdAt,
                        ),
                        sourceVersion = 1,
                    )
                }
            } else {
                v2
            }
            is Decoded.Corrupt -> v2
            is Decoded.WrongKind -> v2
        }

    fun encode(file: ChatFile): ByteArray = Codec.encode(ChatFile.serializer(), file.copy(formatVersion = CHAT_FORMAT_VERSION))
}

@Serializable
enum class SoulState {
    /** SOUL.md vorhanden und nicht leer. */
    @SerialName("present") PRESENT,

    /** SOUL.md vorhanden, aber leer oder nur Leerraum. */
    @SerialName("empty") EMPTY,

    /** SOUL.md nicht vorhanden. */
    @SerialName("missing") MISSING,
}

@Serializable
data class SoulSnapshotFile(
    val format: String = KIND,
    val formatVersion: Int = FORMAT_VERSION,
    val chatId: String,
    val takenAt: String,
    val state: SoulState,
    /** Inhalt unverändert; `null` bei [SoulState.MISSING]. */
    val content: String?,
) {
    companion object { const val KIND = "nisaba-soul-snapshot" }
}

@Serializable
enum class Role {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
enum class MessageStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("incomplete") INCOMPLETE,
    @SerialName("failed") FAILED,
}

@Serializable
data class Message(
    val id: String,
    val role: Role,
    /** Markdown, unverändert wie eingegeben bzw. empfangen. */
    val content: String,
    val createdAt: String,
    /** Bei Antworten: ID der beantworteten Nutzernachricht. */
    val replyTo: String? = null,
    val status: MessageStatus,
    /** Bei Antworten: vom Server gemeldetes bzw. angefragtes Modell. */
    val model: String? = null,
)

@Serializable
data class HistoryFile(
    val format: String = KIND,
    val formatVersion: Int = FORMAT_VERSION,
    val chatId: String,
    /** Zählt bei jedem erfolgreichen Schreiben um 1 hoch. */
    val revision: Long,
    val messages: List<Message>,
) {
    companion object { const val KIND = "nisaba-history" }
}

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>

    /** Kein gültiges JSON, falsche Struktur oder unbekannte Felder. */
    data class Corrupt(val detail: String) : Decoded<Nothing>

    /** Andere Dateiart als erwartet. */
    data class WrongKind(val found: String?) : Decoded<Nothing>

    /** Neuere oder unbekannte Formatversion. */
    data class UnsupportedVersion(val found: Int?) : Decoded<Nothing>
}

inline fun <T, R> Decoded<T>.map(f: (T) -> R): Decoded<R> = when (this) {
    is Decoded.Ok -> Decoded.Ok(f(value))
    is Decoded.Corrupt -> this
    is Decoded.WrongKind -> this
    is Decoded.UnsupportedVersion -> this
}

object Codec {
    private val strict = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        (strict.encodeToString(serializer, value) + "\n").toByteArray(Charsets.UTF_8)

    fun <T> decode(serializer: KSerializer<T>, kind: String, bytes: ByteArray, expected: Int = FORMAT_VERSION): Decoded<T> {
        val text = decodeUtf8Strict(bytes) ?: return Decoded.Corrupt("Not valid UTF-8")
        val obj: JsonObject = try {
            strict.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            return Decoded.Corrupt("Not a valid JSON object")
        } catch (e: SerializationException) {
            return Decoded.Corrupt("Not valid JSON")
        }
        val foundKind = runCatching { obj["format"]?.jsonPrimitive?.content }.getOrNull()
        if (foundKind != kind) return Decoded.WrongKind(foundKind)
        val version = runCatching { obj["formatVersion"]?.jsonPrimitive?.int }.getOrNull()
        if (version != expected) return Decoded.UnsupportedVersion(version)
        return try {
            Decoded.Ok(strict.decodeFromJsonElement(serializer, obj))
        } catch (e: SerializationException) {
            Decoded.Corrupt(e.message?.lineSequence()?.firstOrNull() ?: "Invalid structure")
        } catch (e: IllegalArgumentException) {
            Decoded.Corrupt(e.message ?: "Invalid structure")
        }
    }
}

/** Strenges UTF-8-Dekodieren; ungültige Bytefolgen ergeben `null`. Ein BOM wird entfernt. */
fun decodeUtf8Strict(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        return null
    }
    return text.removePrefix("\uFEFF")
}
