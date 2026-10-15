package lumen.app

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lumen.app.camera.CameraController
import lumen.app.camera.Diagnostics

enum class Screen { VIEWFINDER, DIAG, LOG }

/** Screen routing + camera lifecycle ownership. The camera runs only while: foreground && permission && viewfinder. */
class MainViewModel(private val app: LumenApp) : ViewModel() {
    val diag = Diagnostics(app)
    val camera = CameraController(app)
    val log = app.log

    private val _screen = MutableStateFlow(if (ready()) Screen.VIEWFINDER else Screen.DIAG)
    val screen: StateFlow<Screen> = _screen
    private val _permission = MutableStateFlow(false)
    val permission: StateFlow<Boolean> = _permission
    private var foreground = false
    private var returnTo = Screen.DIAG
    private var userZoom = 1f

    init {
        // ID list only (no per-camera contact) so "never touch" can be set before anything else.
        if (_screen.value == Screen.DIAG && diag.rows.value.isEmpty()) diag.loadIds()
    }

    /** Confirmed + verified mapping for THIS firmware exists. */
    fun ready(): Boolean {
        val snap = app.snapshots.load() ?: return false
        return snap.fingerprint == Build.FINGERPRINT && app.gateCore.usableByRole().isNotEmpty()
    }

    fun go(s: Screen) {
        if (s == Screen.LOG) returnTo = _screen.value
        _screen.value = s
        applyCamera()
    }

    fun backFromLog() = go(returnTo)

    fun setPermission(granted: Boolean) { _permission.value = granted; applyCamera() }
    fun setForeground(f: Boolean) { foreground = f; applyCamera() }

    private fun applyCamera() {
        if (foreground && _permission.value && _screen.value == Screen.VIEWFINDER) camera.start() else camera.stop()
    }

    fun setZoom(z: Float) { userZoom = z; camera.requestZoom(z) }

    fun pinch(factor: Float) {
        val st = camera.state.value
        userZoom = (userZoom * factor).coerceIn(st.zoomFloor, st.zoomCeil)
        camera.requestZoom(userZoom)
    }

    override fun onCleared() { camera.stop() }

    companion object {
        fun factory(app: LumenApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
        }
    }
}
