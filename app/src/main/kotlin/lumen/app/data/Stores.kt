package lumen.app.data

import android.content.Context
import lumen.core.GateState
import lumen.core.GateStore
import lumen.core.Snapshot
import lumen.core.snapshotFromJson
import lumen.core.toJson
import java.io.File
import java.util.concurrent.Executors

/** Durable gate state. commit() always runs on a dedicated background thread; callers block until it lands. */
class PrefsGateStore(ctx: Context) : GateStore {
    private val prefs = ctx.getSharedPreferences("gate", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "gate-io") }

    override fun load(): GateState = runCatching {
        prefs.getString(KEY, null)?.let { GateState.fromJson(it) } ?: GateState()
    }.getOrDefault(GateState())

    override fun save(state: GateState) {
        val json = state.toJson()
        val ok = io.submit<Boolean> { prefs.edit().putString(KEY, json).commit() }.get()
        if (!ok) android.util.Log.e(EventLog.TAG, "gate state commit() returned false")
    }

    private companion object { const val KEY = "state" }
}

/** Snapshot cache (also written after every camera so a late crash keeps earlier progress). */
class SnapshotStore(ctx: Context) {
    private val file = File(ctx.filesDir, "snapshot.json")
    @Synchronized fun load(): Snapshot? = runCatching { if (file.exists()) snapshotFromJson(file.readText()) else null }.getOrNull()
    @Synchronized fun save(s: Snapshot) {
        val tmp = File(file.parentFile, "snapshot.json.tmp")
        tmp.writeText(s.toJson()); tmp.renameTo(file)
    }
}
