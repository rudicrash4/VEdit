package id.vedit.editor

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** Ringkasan encoder/decoder nyata di perangkat (dipakai Phase 0 untuk menilai kelayakan export). */
data class CodecSummary(val name: String, val isHardware: Boolean, val maxInstances: Int?)

object CodecInfo {
    fun list(mime: String, encoder: Boolean): List<CodecSummary> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .map { info ->
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                CodecSummary(
                    name = info.name,
                    isHardware = isHardware(info),
                    maxInstances = caps?.maxSupportedInstances,
                )
            }

    private fun isHardware(info: MediaCodecInfo): Boolean = info.isHardwareAccelerated
}
