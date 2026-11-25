package id.vedit.editor.preview

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.common.Effect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import id.vedit.editor.domain.Clip
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.PreviewPlan.Item
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TrackType
import id.vedit.editor.poc.SteppedSpeedProvider
import id.vedit.editor.poc.constantSegments

/**
 * Project -> Media3 Composition. SATU definisi komposisi untuk preview (dan nanti export):
 * track video = satu sequence (klip + celah), track audio = sequence audio-only terpisah.
 * Rotasi model = searah jarum jam; ScaleAndRotateTransformation berlawanan arah, jadi sudutnya dinegasikan.
 */
@OptIn(UnstableApi::class)
object CompositionFactory {
    const val OUT_WIDTH = 1280
    const val OUT_HEIGHT = 720

    fun build(p: Project): Composition? {
        val sequences = ArrayList<EditedMediaItemSequence>()
        p.tracks.firstOrNull { it.type == TrackType.VIDEO }?.let { track ->
            val anyAudio = track.clips.any { p.source(it.sourceId)?.hasAudio == true }
            val types = if (anyAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
            sequence(p, track.let { PreviewPlan.sequenceItems(it) }, types)?.let { sequences += it }
        }
        p.tracks.firstOrNull { it.type == TrackType.AUDIO }?.let { track ->
            sequence(p, PreviewPlan.sequenceItems(track), setOf(C.TRACK_TYPE_AUDIO))?.let { sequences += it }
        }
        if (sequences.isEmpty()) return null
        return Composition.Builder(sequences).build()
    }

    private fun sequence(p: Project, items: List<Item>, types: Set<Int>): EditedMediaItemSequence? {
        val b = EditedMediaItemSequence.Builder(types)
        var clips = 0
        for (item in items) {
            when (item) {
                is Item.Gap -> b.addGap(item.durationUs)
                is Item.ClipItem -> {
                    val edited = editedItem(p, item.clip)
                    if (edited != null) {
                        b.addItem(edited)
                        clips++
                    } else {
                        b.addGap(item.clip.durationUs) // sumber hilang: pertahankan durasi timeline
                    }
                }
            }
        }
        return if (clips == 0) null else b.build()
    }

    private fun editedItem(p: Project, clip: Clip): EditedMediaItem? {
        val src = p.source(clip.sourceId) ?: return null
        val uri = Uri.parse(src.uri)
        val builder: EditedMediaItem.Builder
        when (src.type) {
            MediaType.IMAGE -> {
                val item = MediaItem.Builder().setUri(uri).setImageDurationMs(maxOf(1L, clip.durationUs / 1000)).build()
                builder = EditedMediaItem.Builder(item).setDurationUs(clip.durationUs).setFrameRate(30)
            }
            else -> {
                val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInUs / 1000)
                        .setEndPositionMs(clip.sourceOutUs / 1000)
                        .build(),
                ).build()
                builder = EditedMediaItem.Builder(item).setDurationUs(src.durationUs)
            }
        }
        val audioProcessors = if (clip.volume != 1f && src.type != MediaType.IMAGE) {
            listOf(ChannelMixingAudioProcessor().also {
                it.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(clip.volume))
            })
        } else {
            emptyList()
        }
        val videoEffects = if (src.type == MediaType.AUDIO) emptyList() else videoEffects(clip)
        builder.setEffects(Effects(audioProcessors, videoEffects))
        if (clip.speed != 1f && src.type != MediaType.IMAGE) {
            builder.setSpeed(SteppedSpeedProvider(constantSegments(clip.sourceOutUs - clip.sourceInUs, clip.speed)))
        }
        return builder.build()
    }

    private fun videoEffects(clip: Clip): List<Effect> {
        val effects = ArrayList<Effect>()
        clip.crop?.let {
            // fraksi sisi yang dipotong -> koordinat NDC (-1..1), y ke atas
            effects += Crop(-1f + 2f * it.left, 1f - 2f * it.right, -1f + 2f * it.bottom, 1f - 2f * it.top)
        }
        if (clip.rotationDeg != 0) {
            effects += ScaleAndRotateTransformation.Builder().setRotationDegrees(-clip.rotationDeg.toFloat()).build()
        }
        effects += Presentation.createForWidthAndHeight(OUT_WIDTH, OUT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)
        return effects
    }
}
