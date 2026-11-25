package id.vedit.editor.data

import id.vedit.editor.domain.Project
import java.io.File
import java.io.FileOutputStream

/**
 * Penyimpanan proyek berbasis file: <root>/<id>/project.json (+ .bak dari versi sebelumnya).
 * Penulisan atomik: tulis ke .tmp, fsync, lalu rename. Jika file utama rusak, pulih dari .bak.
 * Room tidak dipakai di tahap ini: indeks proyek cukup dibaca dari direktori (lihat docs/PHASE1_REPORT.md).
 */
class ProjectStore(private val root: File, private val codec: ProjectCodec = ProjectCodec()) {
    class Loaded(val project: Project, val recoveredFromBackup: Boolean)

    private fun dir(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "id proyek tidak valid" }
        return File(root, id)
    }

    fun save(project: Project) {
        val d = dir(project.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        val main = File(d, "project.json")
        FileOutputStream(tmp).use { out ->
            out.write(codec.encode(project).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (main.exists()) main.copyTo(File(d, "project.json.bak"), overwrite = true)
        // rename(2) pada filesystem yang sama bersifat atomik dan menggantikan target di Android/Linux;
        // java.nio.file baru ada di API 26 sehingga tidak dipakai (minSdk 24).
        if (!tmp.renameTo(main)) {
            main.delete()
            check(tmp.renameTo(main)) { "gagal mengganti project.json" }
        }
    }

    fun load(id: String): Result<Loaded> {
        val d = dir(id)
        val main = File(d, "project.json")
        val bak = File(d, "project.json.bak")
        val first = if (main.exists()) codec.decode(main.readText()) else Result.failure(IllegalStateException("project.json tidak ada"))
        first.getOrNull()?.let { return Result.success(Loaded(it, false)) }
        if (bak.exists()) {
            codec.decode(bak.readText()).getOrNull()?.let { return Result.success(Loaded(it, true)) }
        }
        return Result.failure(first.exceptionOrNull() ?: IllegalStateException("tidak bisa dibaca"))
    }

    /** Daftar proyek yang bisa dibaca, terbaru dulu. Proyek rusak yang tak bisa dipulihkan dilewati. */
    fun list(): List<Project> =
        (root.listFiles { f -> f.isDirectory }?.toList() ?: emptyList())
            .mapNotNull { runCatching { load(it.name).getOrNull()?.project }.getOrNull() }
            .sortedByDescending { it.modifiedAtMs }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }
}
