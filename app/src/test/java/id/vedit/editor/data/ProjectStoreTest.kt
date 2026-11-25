package id.vedit.editor.data

import id.vedit.editor.domain.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ProjectStore(File(tmp.root, "projects"))
    private fun proj(id: String, name: String, mod: Long) = Project.create(id, name, 0).copy(modifiedAtMs = mod)

    @Test
    fun saveLoadList() {
        val s = store()
        s.save(proj("a", "A", 10))
        s.save(proj("b", "B", 20))
        val loaded = s.load("a").getOrThrow()
        assertEquals("A", loaded.project.name)
        assertFalse(loaded.recoveredFromBackup)
        assertEquals(listOf("b", "a"), s.list().map { it.id }) // terbaru dulu
    }

    @Test
    fun corruptMainFileRecoversFromBackup() {
        val s = store()
        s.save(proj("a", "versi1", 1))
        s.save(proj("a", "versi2", 2)) // versi1 menjadi .bak
        File(tmp.root, "projects/a/project.json").writeText("{rusak")
        val r = s.load("a").getOrThrow()
        assertTrue(r.recoveredFromBackup)
        assertEquals("versi1", r.project.name)
    }

    @Test
    fun unrecoverableProjectFailsAndIsSkippedInList() {
        val s = store()
        s.save(proj("good", "ok", 1))
        s.save(proj("bad", "x", 1))
        File(tmp.root, "projects/bad/project.json").writeText("garbage")
        File(tmp.root, "projects/bad/project.json.bak").delete()
        assertTrue(s.load("bad").isFailure)
        assertEquals(listOf("good"), s.list().map { it.id })
    }

    @Test
    fun noTempFileRemainsAndDeleteWorks() {
        val s = store()
        s.save(proj("a", "A", 1))
        assertFalse(File(tmp.root, "projects/a/project.json.tmp").exists())
        s.delete("a")
        assertTrue(s.load("a").isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversalId() {
        store().load("../etc")
    }
}
