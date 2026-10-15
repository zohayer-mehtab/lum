package lumen.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Purpose { DIAG_READ, PROBE, OPEN }

sealed interface Decision {
    data object Allow : Decision
    data class Refuse(val reason: String) : Decision
}

fun interface EventSink { fun log(kind: String, id: String?, msg: String) }

@Serializable
data class GateState(
    val fingerprint: String = "",
    val neverTouch: Set<String> = emptySet(),
    val quarantined: Map<String, String> = emptyMap(),   // id -> reason
    val verified: Set<String> = emptySet(),
    val confirmed: Map<String, Role> = emptyMap(),       // user-confirmed roles
    val marker: String? = null,                          // "probing <id>" in flight
) {
    fun toJson(): String = StateJson.encodeToString(serializer(), this)
    companion object {
        val StateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): GateState = StateJson.decodeFromString(serializer(), s)
    }
}

/** Persistence seam. save() MUST be a durable blocking write (SharedPreferences.commit()) and
 *  is only invoked from non-main threads. */
interface GateStore {
    fun load(): GateState
    fun save(state: GateState)
}

/**
 * Pure decision logic + persisted allowlist/quarantine/marker state machine.
 * The Android-side CameraGate is the only caller that may touch a camera, and only after begin() == Allow.
 */
class GateCore(private val store: GateStore, private val sink: EventSink) {
    private val lock = Any()
    private var state: GateState = store.load()
    private var inFlight: Pair<String, Purpose>? = null

    val snapshot: GateState get() = synchronized(lock) { state }

    /** Call once at launch before anything else. A leftover marker = previous run died mid-touch. */
    fun recoverAtLaunch() {
        synchronized(lock) {
        val m = state.marker ?: return
        val id = m.removePrefix("probing ")
        sink.log("QUARANTINE", id, "stale marker '$m': previous run died mid-touch; quarantined, never auto-retried")
        commit(state.copy(
            marker = null,
            quarantined = state.quarantined + (id to "process died while touching (marker)"),
            verified = state.verified - id,
        ))
        }
    }

    /** Firmware change: verified/confirmed are void; neverTouch and quarantine are kept (conservative). */
    fun syncFingerprint(fp: String): Boolean = synchronized(lock) {
        if (state.fingerprint == fp) return false
        sink.log("FINGERPRINT", null, "changed '${state.fingerprint}' -> '$fp': verification and role mapping reset")
        commit(state.copy(fingerprint = fp, verified = emptySet(), confirmed = emptyMap()))
        true
    }

    fun decide(id: String, purpose: Purpose): Decision = synchronized(lock) { decideLocked(id, purpose) }

    private fun decideLocked(id: String, purpose: Purpose): Decision {
        val s = state
        if (id in s.neverTouch) return Decision.Refuse("never-touch list")
        s.quarantined[id]?.let { return Decision.Refuse("quarantined: $it") }
        inFlight?.let { return Decision.Refuse("another touch in flight (${it.first}/${it.second})") }
        val role = s.confirmed[id]
        when (purpose) {
            Purpose.DIAG_READ -> if (role == Role.PERISCOPE) return Decision.Refuse("confirmed periscope: no reads")
            Purpose.PROBE -> {
                if (role == null) return Decision.Refuse("role not confirmed")
                if (role !in USABLE_ROLES) return Decision.Refuse("role $role is not probe-able")
            }
            Purpose.OPEN -> {
                if (role == null || role !in USABLE_ROLES) return Decision.Refuse("role ${role ?: "unconfirmed"} not usable")
                if (id !in s.verified) return Decision.Refuse("not verified by probe")
            }
        }
        return Decision.Allow
    }

    /** Decide, then durably write the marker BEFORE the caller touches the camera. */
    fun begin(id: String, purpose: Purpose): Decision = synchronized(lock) {
        val d = decideLocked(id, purpose)
        if (d is Decision.Refuse) { sink.log("REFUSE", id, "$purpose: ${d.reason}"); return d }
        commit(state.copy(marker = "probing $id"))
        inFlight = id to purpose
        sink.log(purpose.name, id, "begin (marker written)")
        d
    }

    /** Clear the marker. A successful PROBE adds the id to the verified allowlist. */
    fun end(id: String, purpose: Purpose, ok: Boolean, note: String = "") {
        synchronized(lock) {
        if (inFlight?.first != id) { sink.log("WARN", id, "end() without matching begin()"); return }
        inFlight = null
        var s = state.copy(marker = null)
        if (purpose == Purpose.PROBE && ok) s = s.copy(verified = s.verified + id)
        if (purpose == Purpose.PROBE && !ok) s = s.copy(verified = s.verified - id)
        sink.log(purpose.name, id, (if (ok) "ok " else "FAIL ") + note)
        commit(s)
        }
    }

    fun setNeverTouch(id: String, on: Boolean) = synchronized(lock) {
        sink.log("NEVER_TOUCH", id, if (on) "added" else "removed")
        commit(state.copy(
            neverTouch = if (on) state.neverTouch + id else state.neverTouch - id,
            verified = if (on) state.verified - id else state.verified,
        ))
    }

    /** User confirms/corrects a role. A periscope (or non-usable) role auto-quarantines the id. */
    fun confirmRole(id: String, role: Role) = synchronized(lock) {
        var s = state.copy(confirmed = state.confirmed + (id to role))
        if (state.confirmed[id] != role) s = s.copy(verified = s.verified - id)
        if (role == Role.PERISCOPE) {
            s = s.copy(quarantined = s.quarantined + (id to "classified periscope"), verified = s.verified - id)
        }
        sink.log("CONFIRM", id, "role=$role")
        commit(s)
    }

    fun unquarantine(id: String) = synchronized(lock) {
        sink.log("UNQUARANTINE", id, "manual")
        commit(state.copy(quarantined = state.quarantined - id, verified = state.verified - id))
    }

    fun quarantine(id: String, reason: String) = synchronized(lock) {
        sink.log("QUARANTINE", id, reason)
        commit(state.copy(quarantined = state.quarantined + (id to reason), verified = state.verified - id))
    }

    /** Fixed probe order of rising suspicion. Roles absent are skipped. */
    fun probeOrder(): List<String> = synchronized(lock) {
        val order = listOf(Role.MAIN, Role.FRONT, Role.ULTRA_WIDE, Role.TELE_3X)
        order.flatMap { r -> state.confirmed.filterValues { it == r }.keys.sorted() }
    }

    /** Verified ids by confirmed role, quarantine/neverTouch applied. */
    fun usableByRole(): Map<Role, String> = synchronized(lock) {
        state.verified.filter { it !in state.neverTouch && it !in state.quarantined }
            .mapNotNull { id -> state.confirmed[id]?.takeIf { it in USABLE_ROLES }?.let { it to id } }
            .toMap()
    }

    private fun commit(s: GateState) { state = s; store.save(s) }
}
