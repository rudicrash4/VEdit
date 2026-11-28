package id.vedit.editor.preview

import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/** Membuat/melepas SurfaceView di dalam wadah untuk mesin lama (CompositionPlayer, ExoPlayer). */
internal class SurfaceHost {
    var view: SurfaceView? = null
        private set

    fun attach(host: FrameLayout?): SurfaceView? {
        detach()
        if (host != null) {
            view = SurfaceView(host.context).also {
                host.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        return view
    }

    fun detach() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }
}
