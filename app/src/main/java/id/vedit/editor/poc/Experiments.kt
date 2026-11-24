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
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
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

class Experiment(
    val id: String,
    val title: String,
    val what: String,
    val kind: Kind = Kind.EXPORT,
    /** Durasi keluaran yang diharapkan secara analitis (ms); null = tidak ada ekspektasi numerik. */
    val expectedMs: Long?,
    val expectAudio: Boolean,
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

    private fun item(
        mediaItem: MediaItem,
        effects: Effects = Effects.EMPTY,
        segments: List<SpeedSegment>? = null,
    ): EditedMediaItem {
        val b = EditedMediaItem.Builder(mediaItem).setEffects(effects)
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
                OverlaySettings.Builder().build()
            } else {
                OverlaySettings.Builder()
                    .setScale(0.3f, 0.3f)
                    .setBackgroundFrameAnchor(0.9f, -0.9f)
                    .setOverlayFrameAnchor(1f, -1f)
                    .build()
            }
    }

    fun build(context: Context, inp: PocInputs): List<Experiment> {
        val l1 = min(inp.video1Ms, CLIP_MS)
        val l2 = min(inp.video2Ms, CLIP_MS)
        val list = ArrayList<Experiment>()

        list += Experiment(
            "seq", "A. Dua klip berurutan (1 sequence)",
            "Klip 1 lalu klip 2 dalam satu sequence; durasi harus = jumlah keduanya.",
            expectedMs = l1 + l2, expectAudio = true,
        ) {
            Composition.Builder(listOf(avSequence(item(clip(inp.video1, l1)), item(clip(inp.video2, l2))))).build()
        }

        list += Experiment(
            "pip", "A2. Dua track video bertumpuk (PiP)",
            "Dua sequence paralel: klip 1 latar, klip 2 kecil kanan bawah. Cek thumbnail secara visual.",
            expectedMs = maxOf(l1, l2), expectAudio = true,
        ) {
            Composition.Builder(listOf(avSequence(item(clip(inp.video1, l1))), avSequence(item(clip(inp.video2, l2)))))
                .setVideoCompositorSettings(pipSettings).build()
        }

        for (speed in listOf(1f, 0.5f, 2f)) {
            list += Experiment(
                "speed_$speed", "B. Kecepatan konstan ${speed}x",
                "setSpeed(SpeedProvider) satu segmen; durasi diharapkan = sumber / kecepatan.",
                expectedMs = outputDurationUs(constantSegments(l1 * MS, speed)) / MS, expectAudio = true,
            ) {
                Composition.Builder(listOf(avSequence(item(clip(inp.video1, l1), segments = constantSegments(l1 * MS, speed))))).build()
            }
        }

        val curve = SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(l1 * MS / 2, 4f), SpeedPoint(l1 * MS, 0.5f)))
        val rampSegs = curve.toSegments(l1 * MS, 250_000)
        list += Experiment(
            "ramp", "B2. Speed ramp 1x, 4x, 0.5x (kurva, langkah 250 ms)",
            "Kurva didiskretisasi jadi ${rampSegs.size} segmen lewat SpeedProvider kustom. Durasi diharapkan = integral analitis.",
            expectedMs = outputDurationUs(rampSegs) / MS, expectAudio = true,
        ) {
            Composition.Builder(listOf(avSequence(item(clip(inp.video1, l1), segments = rampSegs)))).build()
        }

        if (inp.audio != null) {
            val la = min(inp.audioMs, CLIP_MS)
            list += Experiment(
                "audio_mix", "C. Video + track audio tambahan (volume 30%)",
                "Sequence audio-only dicampur dengan audio video. Volume harus DIDENGAR manual; PoC ini hanya memverifikasi stream dan durasi.",
                expectedMs = maxOf(l1, la), expectAudio = true,
            ) {
                Composition.Builder(
                    listOf(
                        avSequence(item(clip(inp.video1, l1))),
                        audioSequence(item(clip(inp.audio, la), effects = volume(0.3f))),
                    ),
                ).build()
            }
        }

        list += Experiment(
            "preview_seq", "D. Preview (CompositionPlayer) vs export A",
            "Durasi yang dilaporkan CompositionPlayer untuk komposisi A dibandingkan dengan hasil export A.",
            kind = Kind.PREVIEW_DURATION, expectedMs = null, expectAudio = false, comparedWith = "seq",
        ) {
            Composition.Builder(listOf(avSequence(item(clip(inp.video1, l1)), item(clip(inp.video2, l2))))).build()
        }
        return list
    }
}
