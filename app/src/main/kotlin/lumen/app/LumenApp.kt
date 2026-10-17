package lumen.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import lumen.app.camera.CameraGate
import lumen.app.data.EventLog
import lumen.app.data.PrefsGateStore
import lumen.app.data.SnapshotStore
import lumen.app.data.Settings
import lumen.core.GateCore

/** Manual DI graph. */
class LumenApp : Application() {
    lateinit var log: EventLog
    lateinit var gateCore: GateCore
    lateinit var gate: CameraGate
    lateinit var snapshots: SnapshotStore
    lateinit var settings: Settings
    lateinit var camThread: HandlerThread
    lateinit var camHandler: Handler

    override fun onCreate() {
        super.onCreate()
        log = EventLog()
        snapshots = SnapshotStore(this)
        settings = Settings(this)
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread.looper)
        gateCore = GateCore(PrefsGateStore(this), log)
        gateCore.recoverAtLaunch()                    // stale marker => quarantine, before anything else
        gateCore.syncFingerprint(Build.FINGERPRINT)   // firmware change => verification/mapping reset
        gate = CameraGate(this, gateCore, log, camHandler)
    }
}
