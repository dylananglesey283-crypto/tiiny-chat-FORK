package io.github.jenziner.nisaba.core.storage

/**
 * Pfad relativ zum gewählten Projekt-Stammordner, als Liste von Namen.
 * Absolute Pfade gibt es in der Kernlogik nicht; die Android-Schicht bildet
 * diese Pfade auf Document-URIs des Storage Access Framework ab.
 */
@JvmInline
value class RelPath(val segments: List<String>) {
    init {
        require(segments.none { it.isEmpty() || it == "." || it == ".." || it.contains('/') }) {
            "Ungültiger Pfad: $segments"
        }
    }

    val name: String get() = segments.last()
    val parent: RelPath get() = RelPath(segments.dropLast(1))
    fun child(name: String): RelPath = RelPath(segments + name)
    fun sibling(name: String): RelPath = parent.child(name)
    override fun toString(): String = if (segments.isEmpty()) "/" else segments.joinToString("/")

    companion object {
        val ROOT = RelPath(emptyList())
        fun of(vararg names: String) = RelPath(names.toList())
    }
}

/** Fehlerarten, die die Oberfläche unterscheiden muss (Testgruppe A). */
sealed interface StorageError {
    /** Stammordner nicht erreichbar, z. B. USB-Speicher getrennt. */
    data object Unavailable : StorageError

    /** Zugriff verweigert, z. B. Berechtigung entzogen. */
    data object PermissionDenied : StorageError

    data object NotFound : StorageError

    /** Ziel existiert bereits; es wurde nichts überschrieben. */
    data object AlreadyExists : StorageError

    /** Anbieter hat einen anderen Namen vergeben als verlangt (z. B. "x (1)"). */
    data class NameChanged(val requested: String, val actual: String) : StorageError

    data class Io(val message: String) : StorageError
}

sealed interface SResult<out T> {
    data class Ok<T>(val value: T) : SResult<T>
    data class Err(val error: StorageError) : SResult<Nothing>
}

inline fun <T, R> SResult<T>.map(f: (T) -> R): SResult<R> = when (this) {
    is SResult.Ok -> SResult.Ok(f(value))
    is SResult.Err -> this
}

inline fun <T> SResult<T>.onErr(f: (StorageError) -> Nothing): T = when (this) {
    is SResult.Ok -> value
    is SResult.Err -> f(error)
}

/** [size] in Bytes, sofern der Speicher sie meldet (nur Dateien). */
data class Entry(val name: String, val isDirectory: Boolean, val size: Long? = null)

/**
 * Minimale Dateischnittstelle für den Projekt-Speicher.
 *
 * Bewusst gibt es keine Operation, die den Inhalt einer bestehenden Datei
 * überschreibt. Inhalte werden nur in neu angelegte Dateien geschrieben;
 * das Ersetzen geschieht über [SafeReplace] mit Umbenennen. Damit kann ein
 * abgebrochener Schreibvorgang eine bestehende, gültige Datei nicht
 * teilweise überschreiben.
 */
interface StorageBackend {
    /** Prüft, ob der Stammordner erreichbar und lesbar ist. */
    fun checkRoot(): SResult<Unit>

    fun list(dir: RelPath): SResult<List<Entry>>

    /** Liefert `Ok(null)`, wenn der Eintrag nicht existiert. */
    fun stat(path: RelPath): SResult<Entry?>

    fun read(path: RelPath): SResult<ByteArray>

    /** Legt eine neue Datei an. Existiert sie bereits: [StorageError.AlreadyExists]. */
    fun createFile(path: RelPath, bytes: ByteArray): SResult<Unit>

    /** Legt einen Ordner an. Existiert er bereits: [StorageError.AlreadyExists]. */
    fun createDirectory(path: RelPath): SResult<Unit>

    /**
     * Benennt innerhalb desselben Ordners um. Existiert das Ziel:
     * [StorageError.AlreadyExists]. Vergibt der Anbieter einen anderen Namen,
     * muss die Implementierung [StorageError.NameChanged] melden.
     */
    fun rename(path: RelPath, newName: String): SResult<Unit>

    /** Löscht Datei oder Ordner (Ordner rekursiv). */
    fun delete(path: RelPath): SResult<Unit>
}
