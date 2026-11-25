package id.vedit.editor.data

import android.os.Handler
import android.os.Looper

class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }
}
