package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.storage.FileStorageBackend
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Test group I: plain-folder storage (app storage on Android TV). */
class I_FileStorageTest {
    private fun backend() = FileStorageBackend(Files.createTempDirectory("tiiny-fs").toFile())

    @Test
    fun `I1 create read list stat`() {
        val s = backend()
        assertIs<SResult.Ok<Unit>>(s.createDirectory(RelPath.of("a")))
        assertIs<SResult.Ok<Unit>>(s.createFile(RelPath.of("a", "f.txt"), "hi".toByteArray()))
        assertEquals("hi", (s.read(RelPath.of("a", "f.txt")) as SResult.Ok).value.toString(Charsets.UTF_8))
        assertEquals(listOf("f.txt"), (s.list(RelPath.of("a")) as SResult.Ok).value.map { it.name })
        assertEquals(null, (s.stat(RelPath.of("nope")) as SResult.Ok).value)
    }

    @Test
    fun `I2 existing files are never overwritten`() {
        val s = backend()
        s.createFile(RelPath.of("f"), "one".toByteArray())
        val r = s.createFile(RelPath.of("f"), "two".toByteArray())
        assertEquals(StorageError.AlreadyExists, (r as SResult.Err).error)
        assertEquals("one", (s.read(RelPath.of("f")) as SResult.Ok).value.toString(Charsets.UTF_8))
    }

    @Test
    fun `I3 rename refuses an existing target and delete is recursive`() {
        val s = backend()
        s.createFile(RelPath.of("x"), byteArrayOf()); s.createFile(RelPath.of("y"), byteArrayOf())
        assertEquals(StorageError.AlreadyExists, (s.rename(RelPath.of("x"), "y") as SResult.Err).error)
        assertIs<SResult.Ok<Unit>>(s.rename(RelPath.of("x"), "z"))
        s.createDirectory(RelPath.of("d")); s.createFile(RelPath.of("d", "k"), byteArrayOf())
        assertIs<SResult.Ok<Unit>>(s.delete(RelPath.of("d")))
        assertEquals(null, (s.stat(RelPath.of("d")) as SResult.Ok).value)
    }

    @Test
    fun `I4 missing parent and missing root are reported`() {
        val s = backend()
        assertEquals(StorageError.NotFound, (s.createFile(RelPath.of("no", "f"), byteArrayOf()) as SResult.Err).error)
        val gone = FileStorageBackend(java.io.File("/nonexistent-tiiny-root"))
        assertEquals(StorageError.Unavailable, (gone.checkRoot() as SResult.Err).error)
    }

    @Test
    fun `I5 project store works on a plain folder`() {
        val st = ProjectStore(backend(), TestEnv())
        assertTrue(st.initializeStore() is StoreStatus.Ready)
        assertTrue(st.openStore() is StoreStatus.Ready)
    }
}
