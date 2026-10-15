package lumen.app.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lumen.app.MainViewModel
import lumen.app.camera.CamUi
import lumen.app.camera.FlashMode
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmtZoom(z: Float) = if (z < 10f) "%.1fx".format(z) else "%.0fx".format(z)

@Composable
fun ViewfinderScreen(
    vm: MainViewModel, hasPermission: Boolean,
    onGrant: () -> Unit, onSettings: () -> Unit, onDiag: () -> Unit,
) {
    val s by vm.camera.state.collectAsStateWithLifecycle()
    var evOpen by remember { mutableStateOf(false) }
    var ring by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(ring) { if (ring != null) { delay(1200); ring = null } }
    LaunchedEffect(s.notice) {
        val n = s.notice
        if (n != null && n.startsWith("Saved")) { delay(2500); vm.camera.dismissNotice() }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().background(Ink)) {
        // ---- top quick-settings row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPermission && s.flashAvailable) {
                val label = when (s.flash) { FlashMode.OFF -> "⚡ Off"; FlashMode.AUTO -> "⚡ Auto"; FlashMode.ON -> "⚡ On" }
                Pill(label, selected = s.flash != FlashMode.OFF) {
                    vm.camera.setFlash(when (s.flash) { FlashMode.OFF -> FlashMode.AUTO; FlashMode.AUTO -> FlashMode.ON; FlashMode.ON -> FlashMode.OFF })
                }
            }
            if (hasPermission && s.evMax > s.evMin) {
                Pill("EV " + evText(s.ev, s.evStep), selected = evOpen || s.ev != 0) { evOpen = !evOpen }
            }
            Box(Modifier.weight(1f))
            Pill("Diagnostics") { onDiag() }
        }
        if (evOpen && s.evMax > s.evMin) {
            Slider(
                value = s.ev.toFloat(), onValueChange = { vm.camera.setEv(it.roundToInt()) },
                valueRange = s.evMin.toFloat()..s.evMax.toFloat(),
                modifier = Modifier.padding(horizontal = 20.dp),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
        }

        if (!hasPermission) {
            PermissionPanel(onGrant, onSettings)
            return@Column
        }

        // ---- preview (4:3 portrait like One UI)
        Box(
            Modifier.fillMaxWidth().aspectRatio(s.aspect).clipToBounds().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        ring = o
                        vm.camera.tapFocus(o.x / size.width, o.y / size.height)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ -> if (z != 1f) vm.pinch(z) }
                },
        ) {
            PreviewSurface(
                Modifier.fillMaxSize(),
                onAvailable = { vm.camera.attachSurface(it) },
                onDestroyed = { vm.camera.detachSurface(it) },
            )
            when (val ui = s.ui) {
                is CamUi.Live -> Unit
                is CamUi.Opening -> Box(Modifier.fillMaxSize().background(Color(0xCC000000)), Alignment.Center) {
                    Text(ui.msg, color = Color.White, fontSize = 15.sp)
                }
                CamUi.Idle -> Box(Modifier.fillMaxSize().background(Color.Black))
                is CamUi.Error -> ErrorPanel(ui.msg, onRetry = { vm.camera.retry() }, onDiag = onDiag)
            }
            ring?.let { r ->
                Box(
                    Modifier.offset { IntOffset((r.x - 36.dp.toPx()).roundToInt(), (r.y - 36.dp.toPx()).roundToInt()) }
                        .size(72.dp).border(2.dp, Accent, CircleShape)
                )
            }
            if (s.ui is CamUi.Live) {
                Text(
                    fmtZoom(s.zoom), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            s.notice?.let { n ->
                Text(
                    n, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xBB000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ---- lower controls
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!s.front && s.presets.isNotEmpty()) {
                val nearest = s.presets.minByOrNull { abs(it - s.zoom) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.presets.forEach { p ->
                        Text(
                            fmtPreset(p), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (p == nearest) Accent else Dim,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.setZoom(p) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            if (!s.front && s.lensPills.isNotEmpty()) {
                val sel = s.lensPills.lastOrNull { it <= s.zoom * 1.001f } ?: s.lensPills.first()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.lensPills.forEach { r -> Pill(fmtPreset(r), selected = r == sel) { vm.setZoom(r) } }
                }
            }
            Shutter(enabled = s.ui is CamUi.Live && !s.capturing, busy = s.capturing) { vm.camera.capture() }
        }
    }
}

private fun fmtPreset(p: Float) = if (abs(p - p.roundToInt()) < 0.05f) "${p.roundToInt()}x" else "%.1fx".format(p)

private fun evText(ev: Int, step: Float): String {
    val v = ev * step
    return if (ev == 0) "0" else "%+.1f".format(v)
}

@Composable
private fun Shutter(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
            .clip(CircleShape).background(if (busy) Dim else if (enabled) Color.White else Color(0xFF55555A))
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun PreviewSurface(modifier: Modifier, onAvailable: (SurfaceTexture) -> Unit, onDestroyed: (SurfaceTexture) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { onAvailable(st) }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { onDestroyed(st); return false }  // controller releases it
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

@Composable
private fun ErrorPanel(msg: String, onRetry: () -> Unit, onDiag: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111114)).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera unavailable", color = Danger, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(msg, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Retry", primary = true, onClick = onRetry)
            Btn("Diagnostics", onClick = onDiag)
        }
    }
}

@Composable
private fun PermissionPanel(onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera permission needed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Lumen only opens cameras you have verified. It needs the camera permission to preview and to run probes.",
            color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Grant", primary = true, onClick = onGrant)
            Btn("App settings", onClick = onSettings)
        }
    }
}
