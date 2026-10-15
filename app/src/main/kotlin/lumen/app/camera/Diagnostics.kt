package lumen.app.camera

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lumen.app.LumenApp
import lumen.core.CameraInfo
import lumen.core.Classification
import lumen.core.Classifier
import lumen.core.GateState
import lumen.core.Purpose
import lumen.core.Role
import lumen.core.Snapshot
import lumen.core.summarize
import java.util.concurrent.Executors

data class IdRow(val id: String, val listed: Boolean, val parent: String? = null)

/** Phase 1 orchestration. All camera contact runs one-ID-at-a-time on a single background thread. */
class Diagnostics(private val app: LumenApp) {
    private val gate = app.gate
    private val core = app.gateCore
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "diag") }
    private val prober = Prober(gate, app.camHandler)

    private val _rows = MutableStateFlow<List<IdRow>>(emptyList())
    private val _cameras = MutableStateFlow<Map<String, CameraInfo>>(emptyMap())
    private val _status = MutableStateFlow("")
    private val _busy = MutableStateFlow(false)
    private val _probeResults = MutableStateFlow<Map<String, String>>(emptyMap())
    private var concurrent: List<List<String>> = emptyList()

    val rows: StateFlow<List<IdRow>> = _rows
    val cameras: StateFlow<Map<String, CameraInfo>> = _cameras
    val status: StateFlow<String> = _status
    val busy: StateFlow<Boolean> = _busy
    val probeResults: StateFlow<Map<String, String>> = _probeResults
    private val _gate = MutableStateFlow(core.snapshot)
    val gate: StateFlow<GateState> = _gate
    private val quick = Executors.newSingleThreadExecutor { Thread(it, "diag-quick") }

    private fun refreshGate() { _gate.value = core.snapshot }

    /** Gate mutations run off the main thread (they end in a durable commit()). */
    private fun mutate(f: () -> Unit) = quick.execute { f(); refreshGate() }
    fun setNeverTouch(id: String, on: Boolean) = mutate { core.setNeverTouch(id, on) }
    fun confirm(id: String, role: Role) = mutate { core.confirmRole(id, role) }
    fun unquarantine(id: String) = mutate { core.unquarantine(id) }

    init {
        // Restore cached readings only if they belong to this firmware.
        app.snapshots.load()?.takeIf { it.fingerprint == Build.FINGERPRINT }?.let { s ->
            _cameras.value = s.cameras.associateBy { it.id }
            _rows.value = s.cameras.map { IdRow(it.id, it.listedInCameraIdList) }
            concurrent = s.concurrentSets
        }
    }

    fun classifications(): List<Classification> = Classifier.classify(_cameras.value.values.toList())

    /** Step 0: ID list only. No per-camera contact, so "never touch" can be set first. */
    fun loadIds() = launch("Reading camera ID list") {
        val ids = runCatching { gate.listIds() }.getOrElse { _status.value = "ID list failed: $it"; return@launch }
        app.log.log("IDLIST", null, ids.toString())
        val known = _rows.value.associateBy { it.id }
        _rows.value = ids.map { known[it]?.copy(listed = true) ?: IdRow(it, true) } +
            _rows.value.filter { it.id !in ids }
        _status.value = "Listed IDs: $ids"
    }

    fun readOne(id: String) = launch("Reading $id") { readInternal(id) }

    fun readAll() = launch("Reading all permitted IDs") {
        for (row in _rows.value.toList()) {
            if (_cameras.value[row.id] != null) continue
            readInternal(row.id)
        }
        if (concurrent.isEmpty()) { concurrent = gate.concurrentIds(); persist() }
    }

    private fun readInternal(id: String) {
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        val r = gate.readCharacteristics(id)
        val info = r.fold(
            { CharacteristicsReader.read(id, row.listed, it) },
            { CameraInfo(id = id, facing = "UNKNOWN", isLogical = false, listedInCameraIdList = row.listed, readError = it.message ?: it.toString()) },
        )
        _cameras.value = _cameras.value + (id to info)
        // sub-IDs of logical cameras that the ID list does not expose become rows (read only on user request)
        val extra = info.physicalIds.filter { p -> _rows.value.none { it.id == p } }.map { IdRow(it, false, id) }
        if (extra.isNotEmpty()) _rows.value = _rows.value + extra
        persist()       // after every camera
        _status.value = "Read $id: " + (info.readError ?: "ok")
    }

    fun probe(id: String) = launch("Probing $id") { probeInternal(id) }

    fun probeAll() = launch("Probing in order") {
        for (id in core.probeOrder()) {
            if (core.snapshot.verified.contains(id)) continue
            probeInternal(id)
        }
    }

    private fun probeInternal(id: String) {
        val info = _cameras.value[id]
        val msg = when {
            info == null -> "read characteristics first"
            core.snapshot.confirmed[id] == Role.PERISCOPE || classifications().firstOrNull { it.id == id }?.role == Role.PERISCOPE ->
                "periscope: no probe"
            core.snapshot.confirmed[id]?.let { r -> core.snapshot.confirmed.count { it.value == r } > 1 } == true ->
                "two ids confirmed with the same role; fix the mapping first"
            _rows.value.firstOrNull { it.id == id }?.listed == false -> "not in camera ID list; Phase 2 never opens via a logical camera"
            else -> prober.probe(info)
        }
        _probeResults.value = _probeResults.value + (id to (msg?.let { "FAIL: $it" } ?: "VERIFIED"))
        _status.value = "Probe $id: ${msg ?: "verified"}"
    }

    fun buildSnapshot(): Snapshot = Snapshot(
        fingerprint = Build.FINGERPRINT, device = "${Build.MANUFACTURER} ${Build.MODEL}", sdk = Build.VERSION.SDK_INT,
        createdAt = System.currentTimeMillis(), listedIds = _rows.value.filter { it.listed }.map { it.id },
        cameras = _cameras.value.values.sortedBy { it.id }, concurrentSets = concurrent,
        neverTouch = core.snapshot.neverTouch.sorted(),
        notes = _probeResults.value.map { "probe ${it.key}: ${it.value}" } +
            core.snapshot.let { g -> listOf("confirmed=${g.confirmed}", "verified=${g.verified}", "quarantined=${g.quarantined}") },
    )

    fun summaryText(): String = summarize(buildSnapshot(), classifications(), core.snapshot)

    private fun persist() = app.snapshots.save(buildSnapshot())

    private fun launch(label: String, block: () -> Unit) {
        if (_busy.value) { _status.value = "Busy; wait for the current step"; return }
        _busy.value = true; _status.value = label
        exec.execute {
            try { block() } catch (e: Exception) { _status.value = "$label failed: $e"; app.log.log("WARN", null, "$label failed: $e") }
            finally { refreshGate(); _busy.value = false }
        }
    }
}
