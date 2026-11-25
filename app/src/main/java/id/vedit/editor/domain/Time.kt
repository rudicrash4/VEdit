package id.vedit.editor.domain

/** Satu-satunya satuan waktu otoritatif: mikrodetik (Long). Detik float tidak pernah disimpan. */
const val US_PER_SECOND = 1_000_000L

/** Panjang klip minimum di timeline (100 ms). */
const val MIN_CLIP_US = 100_000L

/** Toleransi pembulatan antar-klip (konversi kecepatan bisa menggeser 1-2 us). */
const val EPS_US = 2L

const val DEFAULT_IMAGE_DURATION_US = 5_000_000L

/** Nomor frame -> mikrodetik, dibulatkan ke terdekat (setengah naik) untuk fps rasional num/den. */
fun framesToUs(frame: Long, fpsNum: Int, fpsDen: Int): Long {
    require(fpsNum > 0 && fpsDen > 0)
    return Math.floorDiv(frame * US_PER_SECOND * fpsDen * 2 + fpsNum, 2L * fpsNum)
}

/** Mikrodetik -> nomor frame terdekat. Pasangan dengan [framesToUs]: round trip identik (tanpa drift). */
fun usToFrame(us: Long, fpsNum: Int, fpsDen: Int): Long {
    require(fpsNum > 0 && fpsDen > 0)
    return Math.floorDiv(us * fpsNum * 2 + US_PER_SECOND * fpsDen, 2L * US_PER_SECOND * fpsDen)
}

/** "m:ss.d" untuk tampilan (bukan untuk penyimpanan). */
fun formatTime(us: Long): String {
    val tenths = Math.floorDiv(us.coerceAtLeast(0) + 50_000L, 100_000L)
    val sec = tenths / 10
    return "%d:%02d.%d".format(sec / 60, sec % 60, tenths % 10)
}
