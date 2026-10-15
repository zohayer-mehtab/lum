package lumen.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecoveryAndSnapshotTest {
    @Test
    fun restartThenFallbackThenGiveUp() {
        val p = RecoveryPolicy(100)
        assertEquals(RecoveryAction.Restart(100), p.onFailure("0", listOf("0", "3")))
        assertEquals(RecoveryAction.Fallback("3"), p.onFailure("0", listOf("0", "3")))
        assertTrue(p.isUnavailable("0"))
        assertEquals(RecoveryAction.Restart(100), p.onFailure("3", listOf("0", "3")))
        assertEquals(RecoveryAction.GiveUp, p.onFailure("3", listOf("0", "3")))
        p.manualRetry()
        assertFalse(p.isUnavailable("0"))
    }

    @Test
    fun jsonRoundTrip() {
        val s = snap(MAIN, FRONT, UW, TELE, PERI).copy(concurrentSets = listOf(listOf("0", "1")))
        assertEquals(s, snapshotFromJson(s.toJson()))
    }

    @Test
    fun summaryMentionsAllCameras() {
        val s = snap(MAIN, TELE)
        val t = summarize(s, Classifier.classify(s.cameras), GateState())
        assertTrue(t.contains("id=0") && t.contains("id=3"))
    }

    // Drop real exports from the phone into src/test/resources/fixtures/*.json; each must parse and
    // never classify a usable role onto a camera we could not read.
    @Test
    fun realFixturesParse() {
        val dir = File(javaClass.classLoader.getResource("fixtures")?.toURI() ?: return)
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            val s = snapshotFromJson(f.readText())
            Classifier.classify(s.cameras).filter { c -> s.cameras.first { it.id == c.id }.readError != null }
                .forEach { assertEquals(Role.UNKNOWN, it.role) }
        }
    }
}