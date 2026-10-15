package lumen.core

import org.junit.Assert.*
import org.junit.Test

class GateTest {
    private fun gate(s: GateState = GateState(fingerprint = "fp1"), st: MemStore = MemStore(s)) = GateCore(st, NOOP) to st

    @Test fun neverVerifiedMeansNoOpen() {
        val (g, _) = gate()
        g.confirmRole("0", Role.MAIN)
        assertTrue(g.begin("0", Purpose.OPEN) is Decision.Refuse)
    }

    @Test fun probeThenOpenAllowed() {
        val (g, st) = gate()
        g.confirmRole("0", Role.MAIN)
        assertEquals(Decision.Allow, g.begin("0", Purpose.PROBE))
        assertEquals("probing 0", st.s.marker)          // marker durable BEFORE touch
        g.end("0", Purpose.PROBE, ok = true)
        assertNull(st.s.marker); assertTrue("0" in st.s.verified)
        assertEquals(Decision.Allow, g.begin("0", Purpose.OPEN)); g.end("0", Purpose.OPEN, true)
    }

    @Test fun failedProbeDoesNotVerify() {
        val (g, st) = gate(); g.confirmRole("0", Role.MAIN)
        g.begin("0", Purpose.PROBE); g.end("0", Purpose.PROBE, false, "no frames")
        assertFalse("0" in st.s.verified); assertNull(st.s.marker)
    }

    @Test fun periscopeAutoQuarantinedAndNeverProbed() {
        val (g, st) = gate(); g.confirmRole("4", Role.PERISCOPE)
        assertTrue("4" in st.s.quarantined)
        assertTrue(g.begin("4", Purpose.PROBE) is Decision.Refuse)
        assertTrue(g.begin("4", Purpose.DIAG_READ) is Decision.Refuse)
        assertTrue(g.begin("4", Purpose.OPEN) is Decision.Refuse)
    }

    @Test fun quarantineOverridesVerified() {
        val (g, _) = gate(); g.confirmRole("3", Role.TELE_3X)
        g.begin("3", Purpose.PROBE); g.end("3", Purpose.PROBE, true)
        g.quarantine("3", "test")
        assertTrue(g.begin("3", Purpose.OPEN) is Decision.Refuse)
        assertTrue(g.usableByRole().isEmpty())
    }

    @Test fun neverTouchRefusesEverything() {
        val (g, _) = gate(); g.setNeverTouch("5", true)
        Purpose.entries.forEach { assertTrue(g.begin("5", it) is Decision.Refuse) }
    }

    @Test fun staleMarkerQuarantinesAtLaunch() {
        val st = MemStore(GateState(fingerprint = "fp1", marker = "probing 3", verified = setOf("3")))
        val g = GateCore(st, NOOP); g.recoverAtLaunch()
        assertNull(st.s.marker); assertTrue("3" in st.s.quarantined); assertFalse("3" in st.s.verified)
        assertTrue(g.begin("3", Purpose.PROBE) is Decision.Refuse)
    }

    @Test fun unusableRolesRefuseProbe() {
        val (g, _) = gate(); g.confirmRole("0", Role.LOGICAL); g.confirmRole("8", Role.UNKNOWN)
        assertTrue(g.begin("0", Purpose.PROBE) is Decision.Refuse)
        assertTrue(g.begin("8", Purpose.PROBE) is Decision.Refuse)
        assertTrue(g.begin("9", Purpose.PROBE) is Decision.Refuse)   // unconfirmed
    }

    @Test fun concurrentTouchRefused() {
        val (g, _) = gate(); g.confirmRole("0", Role.MAIN); g.confirmRole("1", Role.FRONT)
        assertEquals(Decision.Allow, g.begin("0", Purpose.PROBE))
        assertTrue(g.begin("1", Purpose.PROBE) is Decision.Refuse)
    }

    @Test fun fingerprintChangeResetsVerification() {
        val st = MemStore(GateState(fingerprint = "old", verified = setOf("0"), confirmed = mapOf("0" to Role.MAIN), quarantined = mapOf("4" to "x")))
        val g = GateCore(st, NOOP)
        assertTrue(g.syncFingerprint("new"))
        assertTrue(st.s.verified.isEmpty() && st.s.confirmed.isEmpty()); assertTrue("4" in st.s.quarantined)
        assertFalse(g.syncFingerprint("new"))
    }

    @Test fun probeOrderRisingSuspicion() {
        val (g, _) = gate()
        g.confirmRole("3", Role.TELE_3X); g.confirmRole("2", Role.ULTRA_WIDE); g.confirmRole("1", Role.FRONT); g.confirmRole("0", Role.MAIN)
        assertEquals(listOf("0", "1", "2", "3"), g.probeOrder())
    }

    @Test fun stateJsonRoundTrip() {
        val s = GateState("fp", setOf("1"), mapOf("4" to "r"), setOf("0"), mapOf("0" to Role.MAIN), "probing 2")
        assertEquals(s, GateState.fromJson(s.toJson()))
    }
}
