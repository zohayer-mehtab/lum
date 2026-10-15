package lumen.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lumen.app.ui.DiagnosticsScreen
import lumen.app.ui.LogScreen
import lumen.app.ui.LumenTheme
import lumen.app.ui.ViewfinderScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.factory(application as LumenApp) }
    private var orientation: OrientationEventListener? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setPermission(it) }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.setPermission(granted())
        if (savedInstanceState == null && !granted()) permLauncher.launch(Manifest.permission.CAMERA)
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                vm.camera.setDeviceRotation(((o + 45) / 90 * 90) % 360)
            }
        }
        setContent {
            LumenTheme {
                Root(
                    vm,
                    requestPermission = { permLauncher.launch(Manifest.permission.CAMERA) },
                    openSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                )
            }
        }
    }

    override fun onStart() { super.onStart(); vm.setPermission(granted()); vm.setForeground(true) }
    override fun onResume() { super.onResume(); vm.setPermission(granted()); orientation?.enable() }   // catches revocation
    override fun onPause() { orientation?.disable(); super.onPause() }
    override fun onStop() { vm.setForeground(false); super.onStop() }
}

@Composable
private fun Root(vm: MainViewModel, requestPermission: () -> Unit, openSettings: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val perm by vm.permission.collectAsStateWithLifecycle()
    val gate by vm.diag.gate.collectAsStateWithLifecycle()
    val ready = remember(gate) { vm.ready() }

    BackHandler(enabled = screen == Screen.LOG) { vm.backFromLog() }
    BackHandler(enabled = screen == Screen.DIAG && ready) { vm.go(Screen.VIEWFINDER) }

    when (screen) {
        Screen.VIEWFINDER -> ViewfinderScreen(
            vm, perm, onGrant = requestPermission, onSettings = openSettings,
            onDiag = { vm.go(Screen.DIAG) },
        )
        Screen.DIAG -> DiagnosticsScreen(
            vm, perm, ready, onGrant = requestPermission,
            onOpenCamera = { vm.go(Screen.VIEWFINDER) }, onLog = { vm.go(Screen.LOG) },
        )
        Screen.LOG -> LogScreen(vm, onBack = { vm.backFromLog() })
    }
}
