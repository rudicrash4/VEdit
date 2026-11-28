package id.vedit.editor.preview.v2

/**
 * Log diagnostik preview (ring buffer). Hanya berisi kejadian teknis (indeks segmen, state, posisi, kode galat);
 * TIDAK PERNAH memuat URI, nama file, atau isi media pengguna.
 */
object PreviewLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val t0 = System.nanoTime()

    /** Dipasang aplikasi untuk meneruskan ke Logcat; null di tes JVM. */
    @Volatile var dropped = 0L
    @Volatile var codecErrors = 0
    @Volatile var audioUnderruns = 0

    @Volatile
    var sink: ((String) -> Unit)? = null

    @Synchronized
    fun log(tag: String, msg: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        val line = "+${ms}ms $tag $msg"
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast(line)
        sink?.invoke(line)
    }

    @Synchronized
    fun lastLines(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
        codecErrors = 0
        audioUnderruns = 0
    }
}
