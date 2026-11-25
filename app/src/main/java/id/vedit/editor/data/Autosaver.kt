package id.vedit.editor.data

import id.vedit.editor.domain.Project

fun interface Cancellable {
    fun cancel()
}

interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** Autosave dengan debounce: perubahan beruntun hanya menyimpan sekali setelah `delayMs` tenang. */
class Autosaver(
    private val scheduler: Scheduler,
    private val delayMs: Long,
    private val save: (Project) -> Unit,
) {
    private var pending: Project? = null
    private var handle: Cancellable? = null

    fun onChanged(project: Project) {
        pending = project
        handle?.cancel()
        handle = scheduler.schedule(delayMs) { flushNow() }
    }

    /** Simpan segera bila ada perubahan tertunda (dipanggil saat aplikasi berhenti/ditutup). */
    fun flushNow() {
        handle?.cancel()
        handle = null
        val p = pending ?: return
        pending = null
        save(p)
    }
}
