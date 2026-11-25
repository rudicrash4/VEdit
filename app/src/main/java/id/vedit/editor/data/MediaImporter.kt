package id.vedit.editor.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import id.vedit.editor.domain.DEFAULT_IMAGE_DURATION_US
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import java.util.UUID

/** Import via Storage Access Framework: izin baca dipertahankan bila didukung; metadata dibaca nyata dari file. */
class MediaImporter(private val context: Context) {
    class Result(val sources: List<MediaSource>, val failures: List<String>)

    fun import(uris: List<Uri>, type: MediaType): Result {
        val ok = ArrayList<MediaSource>()
        val bad = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri)
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ok += probe(uri, type, name)
            } catch (t: Throwable) {
                bad += "$name: ${t.message ?: t::class.java.simpleName}"
            }
        }
        return Result(ok, bad)
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "media"

    private fun probe(uri: Uri, type: MediaType, name: String): MediaSource {
        val id = UUID.randomUUID().toString()
        if (type == MediaType.IMAGE) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            require(o.outWidth > 0 && o.outHeight > 0) { "gambar tidak bisa dibaca" }
            return MediaSource(id, uri.toString(), type, name, DEFAULT_IMAGE_DURATION_US, o.outWidth, o.outHeight)
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            require(durMs != null && durMs > 0) { "durasi tidak terbaca" }
            if (type == MediaType.AUDIO) {
                return MediaSource(id, uri.toString(), type, name, durMs * 1000, hasAudio = true)
            }
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            require(w > 0 && h > 0) { "bukan video yang valid" }
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return MediaSource(id, uri.toString(), type, name, durMs * 1000, w, h, hasAudio)
        } finally {
            r.release()
        }
    }
}
