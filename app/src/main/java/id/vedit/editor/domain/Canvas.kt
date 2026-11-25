package id.vedit.editor.domain

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Rasio kanvas proyek (lebar:tinggi). */
@Serializable
enum class AspectPreset(val w: Int, val h: Int, val label: String) {
    R16_9(16, 9, "16:9"),
    R9_16(9, 16, "9:16"),
    R1_1(1, 1, "1:1"),
    R4_5(4, 5, "4:5"),
    ;

    val ratio: Float get() = w.toFloat() / h
}

/** Kebijakan penempatan media di kanvas: FIT = semua terlihat (mungkin ada bar kosong); FILL = memenuhi kanvas (tepi bisa terpotong). */
@Serializable
enum class FitMode { FIT, FILL }

/** Pengaturan kanvas yang disimpan di proyek. Resolusi output TIDAK disimpan di sini (dipilih saat export). */
@Serializable
data class CanvasSettings(val aspect: AspectPreset = AspectPreset.R16_9, val fit: FitMode = FitMode.FIT)

/**
 * Kualitas keluaran = sisi pendek frame. PREVIEW lebih rendah demi kelancaran dan TIDAK dipakai export.
 * `exportEnabled` hanya true untuk yang sudah terbukti diekspor di perangkat nyata (720p, uji OPPO A18 v0.4.0).
 */
enum class OutputQuality(val shortSide: Int, val exportEnabled: Boolean) {
    PREVIEW(480, false),
    P720(720, true),
    P1080(1080, false),
}

data class CanvasSize(val width: Int, val height: Int)

/** Tiga konsep terpisah: rasio kanvas (model), resolusi output (kualitas), ukuran tampilan preview (UI, tidak di sini). */
object CanvasSpec {
    private fun even(x: Double): Int = ((x / 2.0).roundToInt() * 2).coerceAtLeast(2)

    fun size(aspect: AspectPreset, shortSide: Int): CanvasSize =
        if (aspect.w >= aspect.h) CanvasSize(even(shortSide * aspect.w.toDouble() / aspect.h), even(shortSide.toDouble()))
        else CanvasSize(even(shortSide.toDouble()), even(shortSide * aspect.h.toDouble() / aspect.w))

    fun size(canvas: CanvasSettings, quality: OutputQuality): CanvasSize = size(canvas.aspect, quality.shortSide)
}

/** Rencana export: ukuran frame mengikuti kanvas proyek. Kualitas yang belum terbukti ditolak. */
object ExportPlan {
    fun sizeFor(p: Project, quality: OutputQuality = OutputQuality.P720): CanvasSize {
        require(quality.exportEnabled) { "Kualitas $quality belum diaktifkan untuk export" }
        return CanvasSpec.size(p.canvas, quality)
    }
}
