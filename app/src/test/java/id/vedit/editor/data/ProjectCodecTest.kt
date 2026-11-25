package id.vedit.editor.data

import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.Track
import id.vedit.editor.domain.TrackType
import id.vedit.editor.domain.Clip
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCodecTest {
    private val sample = Project(
        id = "p1", name = "Proyek Ü",
        sources = listOf(MediaSource("m", "content://x/1", MediaType.VIDEO, "a.mp4", 10_000_000, 1920, 1080, true)),
        tracks = listOf(
            Track("v1", TrackType.VIDEO, listOf(Clip("c", "m", 0, 1_000_000, 9_000_000, 2f, 0.5f, 90, Crop(0.1f, 0f, 0.1f, 0f)))),
            Track("a1", TrackType.AUDIO),
        ),
        createdAtMs = 111, modifiedAtMs = 222,
    )

    @Test
    fun roundTripPreservesEverything() {
        val c = ProjectCodec()
        assertEquals(sample, c.decode(c.encode(sample)).getOrThrow())
    }

    @Test
    fun unknownKeysAreIgnoredButInvalidDataRejected() {
        val c = ProjectCodec()
        val withExtra = c.encode(sample).replaceFirst("{", "{\"futureField\":123,")
        assertEquals(sample, c.decode(withExtra).getOrThrow())
        assertTrue(c.decode("{not json").isFailure)
        assertTrue(c.decode("{\"id\":\"x\"}").isFailure) // tanpa schemaVersion
        // klip tumpang tindih -> ditolak oleh validasi
        val bad = c.encode(sample.copy(tracks = listOf(sample.tracks[0].copy(clips = sample.tracks[0].clips + sample.tracks[0].clips[0].copy(id = "c2")), sample.tracks[1])))
        assertTrue(c.decode(bad).exceptionOrNull()!!.message!!.contains("tidak valid"))
    }

    @Test
    fun newerSchemaIsRejectedNotCorrupted() {
        val future = ProjectCodec(currentVersion = 5).encode(sample)
        val r = ProjectCodec(currentVersion = 1).decode(future)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("lebih baru"))
    }

    @Test
    fun migrationChainRunsInOrder() {
        val v1 = ProjectCodec(currentVersion = 1).encode(sample)
        // Simulasi schema 2 mengganti nama field: v1 -> v2 -> v3 menambah suffix pada name
        val migrations = mapOf<Int, (JsonObject) -> JsonObject>(
            1 to { o: JsonObject -> JsonObject(o + ("name" to JsonPrimitive(o["name"].toString().trim('"') + "|m1"))) },
            2 to { o: JsonObject -> JsonObject(o + ("name" to JsonPrimitive(o["name"].toString().trim('"') + "|m2"))) },
        )
        val migrated = ProjectCodec(migrations, currentVersion = 3).decode(v1).getOrThrow()
        assertEquals("Proyek Ü|m1|m2", migrated.name)
        assertEquals(3, migrated.schemaVersion)
        assertTrue(ProjectCodec(emptyMap(), currentVersion = 3).decode(v1).exceptionOrNull()!!.message!!.contains("Tidak ada migrasi"))
    }
}
