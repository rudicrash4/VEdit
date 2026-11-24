package id.vedit.editor.poc

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlin.math.min

/** Masukan pengguna untuk PoC. Durasi sumber dalam ms. */
data class PocInputs(
    val video1: Uri,
    val video1Ms: Long,
    val video2: Uri,
    val video2Ms: Long,
    val audio: Uri?,
    val audioMs: Long,
)

enum class Kind { EXPORT, PREVIEW_DURATION }

@OptIn(UnstableApi::class)
class Experiment(
    val id: String,
    val title: String,
    val what: String,
    val kind: Kind = Kind.EXPORT,
    /** Durasi keluaran yang diharapkan secara analitis (ms); null = tidak ada ekspektasi numerik. */
    val expectedMs: Long?,
    val expectAudio: Boolean,
    val expectVideo: Boolean = true,
    /** Untuk PREVIEW_DURATION: id eksperimen export yang dibandingkan. */
    val comparedWith: String? = null,
    val build: () -> Composition,
)

const val CLIP_MS = 5_000L
private const val MS = 1_000L

@OptIn(UnstableApi::class)
object Experiments {

    private fun clip(uri: Uri, endMs: Long): MediaItem =
        MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(endMs).build(),
            ).build()

    /**
     * `sourceMs` = durasi sumber TANPA clipping. CompositionPlayer mewajibkan durationUs terisi
     * (tanpa itu: IllegalStateException tanpa pesan); export dan preview memakai definisi yang sama.
     */
    private fun item(
        mediaItem: MediaItem,
        sourceMs: Long,
        effects: Effects = Effects.EMPTY,
        segments: List<SpeedSegment>? = null,
    ): EditedMediaItem {
        val b = EditedMediaItem.Builder(mediaItem).setDurationUs(sourceMs * MS).setEffects(effects)
        if (segments != null) b.setSpeed(SteppedSpeedProvider(segments))
        return b.build()
    }

    private fun avSequence(vararg items: EditedMediaItem): EditedMediaItemSequence =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)).addItems(*items).build()

    private fun audioSequence(vararg items: EditedMediaItem): EditedMediaItemSequence =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItems(*items).build()

    private fun volume(gain: Float): Effects {
        val p = ChannelMixingAudioProcessor()
        p.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(gain))
        return Effects(listOf(p), emptyList())
    }

    /** Video kedua sebagai picture-in-picture kecil di kanan bawah; video pertama sebagai latar penuh. */
    private val pipSettings = object : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
            if (inputId == 0) {
                StaticOverlaySettings.Builder().build()
            } else {
                StaticOverlaySettings.Builder()
                    .setScale(0.3f, 0.3f)
                    .setBackgroundFrameAnchor(0.9f, -0.9f)
                    .setOverlayFrameAnchor(1f, -1f)
                    .build()
            }
    }

    /** Kurva ramp PoC: 1x -> 4x (tengah) -> 0.5x (akhir). */
    fun rampSegments(clipMs: Long): List<SpeedSegment> =
        SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(clipMs * MS / 2, 4f), SpeedPoint(clipMs * MS, 0.5f)))
            .toSegments(clipMs * MS, 250_000)

    const val RAMP_MIN_SPEED = 0.5f

    @Suppress("UNUSED_PARAMETER")
    fun build(context: Context, inp: PocInputs): List<Experiment> {
        val l1 = min(inp.video1Ms, CLIP_MS)
        val l2 = min(inp.video2Ms, CLIP_MS)
        val list = ArrayList<Experiment>()
        fun v1(effects: Effects = Effects.EMPTY, segs: List<SpeedSegment>? = null) =
            item(clip(inp.video1, l1), inp.video1Ms, effects, segs)
        fun v2() = item(clip(inp.video2, l2), inp.video2Ms)

        list += Experiment(
            "seq", "A. Dua klip berurutan (1 sequence)",
            "Klip 1 lalu klip 2 dalam satu sequence; durasi harus = jumlah keduanya.",
            expectedMs = l1 + l2, expectAudio = true,
        ) { Composition.Builder(listOf(avSequence(v1(), v2()))).build() }

        list += Experiment(
            "pip", "A2. Dua track video bertumpuk (PiP)",
            "Dua sequence paralel: klip 1 latar, klip 2 kecil kanan bawah. Dibuktikan otomatis lewat perbandingan piksel dengan klip 1 sendirian.",
            expectedMs = maxOf(l1, l2), expectAudio = true,
        ) {
            Composition.Builder(listOf(avSequence(v1()), avSequence(v2())))
                .setVideoCompositorSettings(pipSettings).build()
        }

        for (speed in listOf(1f, 0.5f, 2f)) {
            list += Experiment(
                "speed_$speed", "B. Kecepatan konstan ${speed}x",
                "setSpeed(SpeedProvider) satu segmen; durasi diharapkan = sumber / kecepatan.",
                expectedMs = outputDurationUs(constantSegments(l1 * MS, speed)) / MS, expectAudio = true,
            ) { Composition.Builder(listOf(avSequence(v1(segs = constantSegments(l1 * MS, speed))))).build() }
        }

        val rampSegs = rampSegments(l1)
        list += Experiment(
            "ramp", "B2. Speed ramp 1x, 4x, 0.5x (kurva, langkah 250 ms)",
            "Kurva didiskretisasi jadi ${rampSegs.size} segmen lewat SpeedProvider kustom. Durasi = integral analitis; kontinuitas frame diukur dari timestamp.",
            expectedMs = outputDurationUs(rampSegs) / MS, expectAudio = true,
        ) { Composition.Builder(listOf(avSequence(v1(segs = rampSegs)))).build() }

        list += Experiment(
            "vol_v", "C0. Volume audio video 50%",
            "Klip 1 dengan gain 0.5; RMS audio dibandingkan dengan klip 1 pada 1x.",
            expectedMs = l1, expectAudio = true,
        ) { Composition.Builder(listOf(avSequence(v1(effects = volume(0.5f))))).build() }

        if (inp.audio != null) {
            val la = min(inp.audioMs, CLIP_MS)
            fun a(gain: Float) = item(clip(inp.audio, la), inp.audioMs, effects = volume(gain))
            list += Experiment(
                "vol_a_1.0", "C1. Audio tambahan saja, volume 100%", "Acuan RMS audio tambahan.",
                expectedMs = la, expectAudio = true, expectVideo = false,
            ) { Composition.Builder(listOf(audioSequence(a(1f)))).build() }
            list += Experiment(
                "vol_a_0.3", "C2. Audio tambahan saja, volume 30%", "RMS harus ~0.3x dari C1.",
                expectedMs = la, expectAudio = true, expectVideo = false,
            ) { Composition.Builder(listOf(audioSequence(a(0.3f)))).build() }
            list += Experiment(
                "audio_mix", "C3. Video + audio tambahan (30%) dicampur",
                "Sequence audio-only dicampur dengan audio klip 1; energi campuran dibandingkan dengan prediksi dari komponen.",
                expectedMs = maxOf(l1, la), expectAudio = true,
            ) { Composition.Builder(listOf(avSequence(v1()), audioSequence(a(0.3f)))).build() }
        }

        for ((id, src, title) in listOf(
            Triple("d_seq", "seq", "D1. Preview vs export: A (berurutan)"),
            Triple("d_pip", "pip", "D2. Preview vs export: A2 (PiP)"),
            Triple("d_ramp", "ramp", "D3. Preview vs export: B2 (ramp)"),
        )) {
            val srcExp = list.first { it.id == src }
            list += Experiment(
                id, title, "CompositionPlayer (tanpa Surface) melaporkan durasi komposisi yang sama dengan export `$src`.",
                kind = Kind.PREVIEW_DURATION, expectedMs = null, expectAudio = false, comparedWith = src,
                build = srcExp.build,
            )
        }
        return list
    }
}
