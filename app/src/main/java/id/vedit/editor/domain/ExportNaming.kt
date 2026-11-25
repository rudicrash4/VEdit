package id.vedit.editor.domain

/** Nama file export yang aman untuk sistem file/MediaStore. Murni agar bisa diuji. */
object ExportNaming {
    fun fileName(projectName: String, timestampMs: Long): String {
        val cleaned = projectName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").trim('_').take(40).ifEmpty { "Proyek" }
        val t = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(timestampMs))
        return "VEdit_${cleaned}_$t.mp4"
    }
}
