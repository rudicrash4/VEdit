package id.vedit.editor.data

import id.vedit.editor.domain.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Serialisasi proyek JSON berversi. `migrations[v]` mengubah dokumen schema v menjadi v+1.
 * Dokumen dari versi lebih baru ditolak (bukan dirusak). Kunci tak dikenal diabaikan dalam versi yang sama.
 */
class ProjectCodec(
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap(),
    private val currentVersion: Int = Project.CURRENT_SCHEMA,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String =
        json.encodeToString(Project.serializer(), project.copy(schemaVersion = currentVersion))

    fun decode(text: String): Result<Project> = runCatching {
        var obj = json.parseToJsonElement(text).jsonObject
        var v = obj["schemaVersion"]?.jsonPrimitive?.intOrNull ?: error("schemaVersion tidak ada")
        if (v > currentVersion) error("Proyek dibuat oleh versi aplikasi lebih baru (schema $v)")
        while (v < currentVersion) {
            val migrate = migrations[v] ?: error("Tidak ada migrasi dari schema $v")
            obj = JsonObject(migrate(obj) + ("schemaVersion" to JsonPrimitive(v + 1)))
            v++
        }
        val project = json.decodeFromJsonElement(Project.serializer(), obj)
        val problems = project.validate()
        check(problems.isEmpty()) { "Proyek tidak valid: ${problems.first()}" }
        project
    }
}
