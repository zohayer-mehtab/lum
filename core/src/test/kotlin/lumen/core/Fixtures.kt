package lumen.core

fun cam(id: String, facing: String = "BACK", focal: Float, w: Float, h: Float, logical: Boolean = false,
        phys: List<String> = emptyList(), listed: Boolean = true) =
    CameraInfo(id = id, facing = facing, isLogical = logical, physicalIds = phys, listedInCameraIdList = listed,
        focalLengths = listOf(focal), sensorWidthMm = w, sensorHeightMm = h, pixelWidth = 4000, pixelHeight = 3000)

// Synthetic optics: equiv35 ~ 24.5 (main), 13.6 (UW, ratio ~0.56), 73 (tele, ~3.0), 245 (periscope, ~10)
val MAIN = cam("0", focal = 6.8f, w = 9.6f, h = 7.2f)
val UW = cam("2", focal = 2.2f, w = 5.6f, h = 4.2f)
val TELE = cam("3", focal = 9.3f, w = 4.4f, h = 3.3f)
val PERI = cam("4", focal = 31f, w = 4.4f, h = 3.3f)
val FRONT = cam("1", facing = "FRONT", focal = 2.5f, w = 4.8f, h = 3.6f)

fun snap(vararg c: CameraInfo, listed: List<String> = c.filter { it.listedInCameraIdList }.map { it.id }) =
    Snapshot(fingerprint = "fp1", device = "SM-G998U", sdk = 34, listedIds = listed, cameras = c.toList())

class MemStore(var s: GateState = GateState()) : GateStore {
    var saves = 0
    override fun load() = s
    override fun save(state: GateState) { s = state; saves++ }
}
val NOOP = EventSink { _, _, _ -> }
