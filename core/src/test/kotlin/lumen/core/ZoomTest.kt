package lumen.core

import org.junit.Assert.*
import org.junit.Test

class ZoomTest {
    private val full = ZoomPlan(listOf(Lens(Role.ULTRA_WIDE, 0.6f, 3f), Lens(Role.MAIN, 1f, 5f), Lens(Role.TELE_3X, 3f, 10f)))
    private fun near(e: Float, a: Float) = assertEquals(e, a, 0.01f)

    @Test fun specExamples() {
        full.route(0.8f).let { assertEquals(Role.ULTRA_WIDE, it.role); near(1.33f, it.crop) }
        full.route(2f).let { assertEquals(Role.MAIN, it.role); near(2f, it.crop) }
        full.route(3f).let { assertEquals(Role.TELE_3X, it.role); near(1f, it.crop) }
        full.route(10f).let { assertEquals(Role.TELE_3X, it.role); near(3.33f, it.crop) }
        full.route(30f).let { assertEquals(Role.TELE_3X, it.role); near(10f, it.crop) }
        full.route(0.6f).let { assertEquals(Role.ULTRA_WIDE, it.role); near(1f, it.crop) }
    }

    @Test fun rangeAndClamp() {
        near(0.6f, full.floor); near(30f, full.ceiling)
        assertEquals(30f, full.clamp(99f), 0.001f)
    }

    @Test fun hysteresisPreventsFlap() {
        assertEquals(Role.TELE_3X, full.route(2.95f, Role.TELE_3X).role)   // stays
        assertEquals(Role.MAIN, full.route(2.95f, Role.MAIN).role)          // not yet up
        assertEquals(Role.MAIN, full.route(2.8f, Role.TELE_3X).role)        // clearly below -> down
        assertEquals(Role.MAIN, full.route(0.99f, Role.MAIN).role)
    }

    @Test fun teleMissingLowerCeiling() {
        val p = ZoomPlan(listOf(Lens(Role.ULTRA_WIDE, 0.6f, 3f), Lens(Role.MAIN, 1f, 5f)))
        near(5f, p.ceiling)
        assertEquals(Role.MAIN, p.route(30f).role)
        assertEquals(5f, p.route(30f).crop, 0.01f)
    }

    @Test fun presetsFiltered() {
        val p = ZoomPlan(listOf(Lens(Role.MAIN, 1f, 5f)))
        assertEquals(listOf(1f, 2f, 3f), presetsFor(p))
    }
}
