package id.vedit.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TrackType
import id.vedit.editor.domain.US_PER_SECOND

/**
 * Timeline: ruler, track video, track audio, klip yang bisa dipilih, playhead. Zoom lewat pxPerSecond.
 * Ketuk area kosong/ruler = memindah playhead; ketuk klip = memilih.
 */
@Composable
fun Timeline(
    project: Project,
    selectedClipId: String?,
    playheadUs: Long,
    dpPerSecond: Float,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val totalUs = maxOf(project.durationUs + 5 * US_PER_SECOND, 10 * US_PER_SECOND)
    val contentWidth = (totalUs / US_PER_SECOND.toFloat() * dpPerSecond).dp
    fun usToDp(us: Long) = (us / US_PER_SECOND.toFloat() * dpPerSecond).dp
    val laneHeight = 56.dp
    val rulerHeight = 24.dp

    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Box(
            Modifier.width(contentWidth).height(rulerHeight + laneHeight * 2 + 8.dp)
                .pointerInput(dpPerSecond) {
                    detectTapGestures { pos ->
                        val dp = with(density) { pos.x.toDp().value }
                        onSeek(((dp / dpPerSecond) * US_PER_SECOND).toLong().coerceIn(0L, totalUs))
                        onSelect(null)
                    }
                },
        ) {
            // ruler
            val seconds = (totalUs / US_PER_SECOND).toInt()
            val step = when { dpPerSecond >= 60f -> 1; dpPerSecond >= 25f -> 2; else -> 5 }
            for (s in 0..seconds step step) {
                Text("${s}s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.offset(x = usToDp(s * US_PER_SECOND) + 2.dp))
            }
            project.tracks.forEachIndexed { i, track ->
                val y = rulerHeight + laneHeight * i
                Box(
                    Modifier.offset(y = y).fillMaxWidth().height(laneHeight - 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
                track.clips.forEach { clip ->
                    val src = project.source(clip.sourceId)
                    val selected = clip.id == selectedClipId
                    val color = if (track.type == TrackType.VIDEO) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                    Box(
                        Modifier.offset(x = usToDp(clip.startUs), y = y).width(usToDp(clip.durationUs)).height(laneHeight - 4.dp)
                            .background(color)
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            .clickable { onSelect(clip.id) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(src?.displayName ?: "?", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            // playhead
            Box(Modifier.offset(x = usToDp(playheadUs)).width(2.dp).height(rulerHeight + laneHeight * 2).background(MaterialTheme.colorScheme.error))
        }
    }
}
