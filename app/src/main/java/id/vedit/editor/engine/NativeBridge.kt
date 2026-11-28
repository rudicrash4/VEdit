package id.vedit.editor.engine

/**
 * Pintu JNI ke pustaka Rust `libvedit_bridge.so`. Semua fungsi mengembalikan String JSON (kontrak versi
 * [EXPECTED_API]) dan tidak melempar exception. Jika pustaka tidak ada/gagal dimuat, [isAvailable] = false
 * dan aplikasi memakai [KotlinEngine].
 */
object NativeBridge {
    /** Versi kontrak yang dipahami kode Kotlin ini; harus sama dengan `vedit_core::API_VERSION`. */
    const val EXPECTED_API = 2

    var loadError: String? = null
        private set

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("vedit_bridge")
            val v = nativeApiVersion()
            if (v != EXPECTED_API) {
                loadError = "Versi API native $v != $EXPECTED_API"
                false
            } else {
                true
            }
        } catch (e: Throwable) { // UnsatisfiedLinkError dll.
            loadError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    @JvmStatic external fun nativeApiVersion(): Int
    @JvmStatic external fun nativeOpen(projectJson: String): String
    @JvmStatic external fun nativeApply(handle: Long, commandJson: String, nowMs: Long): String
    @JvmStatic external fun nativeUndo(handle: Long): String
    @JvmStatic external fun nativeRedo(handle: Long): String
    @JvmStatic external fun nativeSnapshot(handle: Long): String
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePlan(projectJson: String): String
    @JvmStatic external fun nativeLayout(argsJson: String): String
    @JvmStatic external fun nativeCanvasSize(aspect: String, fit: String, quality: String): String
}
