package lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lumen.app.MainViewModel
import lumen.app.camera.IdRow
import lumen.core.CameraInfo
import lumen.core.Classification
import lumen.core.Classifier
import lumen.core.Confidence
import lumen.core.GateState
import lumen.core.Role
import lumen.core.USABLE_ROLES
import lumen.core.toJson

private val CONFIRM_ROLES = listOf(
    Role.ULTRA_WIDE to "UW 0.6x", Role.MAIN to "Main 1x", Role.TELE_3X to "Tele 3x",
    Role.PERISCOPE to "Periscope", Role.FRONT to "Front", Role.UNKNOWN to "Ignore",
)

@Composable
fun DiagnosticsScreen(
    vm: MainViewModel, hasPermission: Boolean, ready: Boolean,
    onGrant: () -> Unit, onOpenCamera: () -> Unit, onLog: () -> Unit,
) {
    val d = vm.diag
    val ctx = LocalContext.current
    val rows by d.rows.collectAsStateWithLifecycle()
    val cams by d.cameras.collectAsStateWithLifecycle()
    val status by d.status.collectAsStateWithLifecycle()
    val busy by d.busy.collectAsStateWithLifecycle()
    val probes by d.probeResults.collectAsStateWithLifecycle()
    val gate by d.gate.collectAsStateWithLifecycle()
    val cls = remember(cams) { Classifier.classify(cams.values.toList()) }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hardware diagnostics", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "1 List IDs and mark any ID Never touch.  2 Read characteristics (one ID at a time, crash-guarded).  " +
                        "3 Confirm or correct each role.  4 Probe each camera.  Then open the camera.",
                    color = Dim, fontSize = 13.sp,
                )
                Text((if (busy) "⏳ " else "") + status, color = if (busy) Accent else Dim, fontSize = 13.sp)
                if (!hasPermission) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Camera permission missing: probes disabled.", color = Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Btn("Grant", onClick = onGrant)
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("1 · List IDs", enabled = !busy) { d.loadIds() }
                Btn("2 · Read all", enabled = !busy && rows.isNotEmpty()) { d.readAll() }
                Btn("4 · Probe all in order", enabled = !busy && hasPermission && gate.confirmed.isNotEmpty()) { d.probeAll() }
                Btn("Event log", onClick = onLog)
            }
        }
        if (rows.isNotEmpty()) item { Text("Camera IDs", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        items(rows, key = { it.id }) { row ->
            IdCard(
                row = row, info = cams[row.id], cls = cls.firstOrNull { it.id == row.id }, gate = gate,
                probe = probes[row.id], busy = busy, hasPermission = hasPermission,
                onNever = { d.setNeverTouch(row.id, it) }, onRead = { d.readOne(row.id) },
                onConfirm = { d.confirm(row.id, it) }, onProbe = { d.probe(row.id) },
                onUnquarantine = { d.unquarantine(row.id) },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Share JSON", enabled = cams.isNotEmpty()) { Share.json(ctx, d.buildSnapshot().toJson(), "lumen-snapshot.json") }
                    Btn("Share summary", enabled = cams.isNotEmpty()) { Share.text(ctx, d.summaryText(), "Lumen diagnostics") }
                    Btn("Copy summary", enabled = cams.isNotEmpty()) { Share.copy(ctx, d.summaryText()) }
                }
                Btn(
                    if (ready) "Open camera" else "Open camera (needs a verified, confirmed camera)",
                    enabled = ready && hasPermission, primary = true, onClick = onOpenCamera,
                )
            }
        }
    }
}

@Composable
private fun IdCard(
    row: IdRow, info: CameraInfo?, cls: Classification?, gate: GateState, probe: String?,
    busy: Boolean, hasPermission: Boolean,
    onNever: (Boolean) -> Unit, onRead: () -> Unit, onConfirm: (Role) -> Unit,
    onProbe: () -> Unit, onUnquarantine: () -> Unit,
) {
    val id = row.id
    val never = id in gate.neverTouch
    val quarantine = gate.quarantined[id]
    val confirmed = gate.confirmed[id]
    val verified = id in gate.verified
    val isPeriscope = cls?.role == Role.PERISCOPE || confirmed == Role.PERISCOPE

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ID $id", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (row.listed) "in ID list" else "NOT in ID list (sub-camera of ${row.parent ?: "?"})")
                        info?.let { append(" · ${it.facing}"); if (it.isLogical) append(" · logical ${it.physicalIds}") }
                    }, color = Dim, fontSize = 12.sp,
                )
            }
            Text("Never touch", fontSize = 12.sp, color = if (never) Danger else Dim, modifier = Modifier.padding(end = 8.dp))
            Switch(checked = never, onCheckedChange = onNever)
        }

        when {
            never -> Text("Marked never-touch: no read, probe or open will happen.", color = Danger, fontSize = 13.sp)
            quarantine != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QUARANTINED: $quarantine", color = Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!isPeriscope) Btn("Release", enabled = !busy, onClick = onUnquarantine)
            }
        }

        if (info == null) {
            Btn("Read characteristics", enabled = !busy && !never && quarantine == null, onClick = onRead)
        } else if (info.readError != null) {
            Text("Read failed: ${info.readError}", color = Danger, fontSize = 13.sp)
            Btn("Retry read", enabled = !busy && !never && quarantine == null, onClick = onRead)
        } else {
            if (cls != null) {
                val col = when (cls.confidence) { Confidence.HIGH -> Good; Confidence.MEDIUM -> Accent; Confidence.LOW -> Danger }
                Text("Suggested: ${cls.role} (${cls.confidence})", color = col, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(cls.reason, color = Dim, fontSize = 12.sp)
            }
            Text(
                "Confirmed: ${confirmed?.name ?: "—"}" + if (verified) "   ✔ VERIFIED" else "",
                color = if (verified) Good else Color.White, fontSize = 14.sp,
            )
            // role confirmation / correction
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CONFIRM_ROLES.forEach { (r, label) ->
                    Pill(label, selected = confirmed == r, tint = if (r == Role.PERISCOPE) Danger else Accent,
                        enabled = !busy && !never) { onConfirm(r) }
                }
                if (cls != null && cls.role == Role.LOGICAL) Pill("Logical", selected = confirmed == Role.LOGICAL, enabled = !busy && !never) { onConfirm(Role.LOGICAL) }
            }
            if (cls != null && cls.confidence != Confidence.LOW && cls.role != Role.UNKNOWN && confirmed != cls.role) {
                Btn("Confirm suggested: ${cls.role}", enabled = !busy && !never, primary = true) { onConfirm(cls.role) }
            }
            // probe: never offered for a periscope or non-usable role
            val canProbe = !isPeriscope && confirmed != null && confirmed in USABLE_ROLES && row.listed &&
                !never && quarantine == null
            if (isPeriscope) {
                Text("Periscope: never probed, never opened. Quarantined.", color = Danger, fontSize = 13.sp)
            } else if (canProbe) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn(if (verified) "Re-probe" else "Probe (open · 5 frames · close)", enabled = !busy && hasPermission, onClick = onProbe)
                    Btn("Re-read", enabled = !busy, onClick = onRead)
                }
            } else if (!row.listed) {
                Text("Reachable only through a logical camera; Phase 2 never opens it.", color = Dim, fontSize = 12.sp)
            }
            probe?.let { Text("Last probe: $it", color = if (it.startsWith("VERIFIED")) Good else Danger, fontSize = 13.sp) }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E0E10)).padding(10.dp)) {
                Text(details(info), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Dim)
            }
        }
    }
}

private fun details(c: CameraInfo) = buildString {
    appendLine("focal ${c.focalLengths}  equiv35 ${"%.1f".format(c.equiv35)}mm")
    appendLine("sensor ${c.sensorWidthMm}x${c.sensorHeightMm}mm  px ${c.pixelWidth}x${c.pixelHeight}")
    appendLine("zoomRatio ${c.zoomRatioMin}..${c.zoomRatioMax}  maxDigital ${c.maxDigitalZoom}")
    appendLine("jpeg ${c.jpegSizes.take(3)}  maxRes ${c.maxResJpegSizes.take(2)}")
    appendLine("video ${c.videoSizes.take(3)}  fps ${c.fpsRanges.take(5)}")
    append("flash ${c.flashAvailable}  hwLevel ${c.hardwareLevel}  caps ${c.capabilities}")
}
