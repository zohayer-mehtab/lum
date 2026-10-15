package lumen.core

import org.junit.Assert.*
import org.junit.Test

class ClassifierTest {
    private fun roles(vararg c: CameraInfo) = Classifier.classify(c.toList()).associate { it.id to it }

    @Test fun standalonePhysicalWithPeriscope() {
        val r = roles(MAIN, FRONT, UW, TELE, PERI)
        assertEquals(Role.MAIN, r["0"]!!.role); assertEquals(Role.FRONT, r["1"]!!.role)
        assertEquals(Role.ULTRA_WIDE, r["2"]!!.role); assertEquals(Role.TELE_3X, r["3"]!!.role)
        assertEquals(Role.PERISCOPE, r["4"]!!.role)
        assertTrue(r.values.all { it.confidence == Confidence.HIGH })
    }

    @Test fun periscopeAbsent() {
        val r = roles(MAIN, FRONT, UW, TELE)
        assertTrue(r.values.none { it.role == Role.PERISCOPE })
        assertEquals(Role.TELE_3X, r["3"]!!.role)
    }

    @Test fun logicalWithPhysicalSubIds() {
        val logical = cam("9", focal = 6.8f, w = 9.6f, h = 7.2f, logical = true, phys = listOf("0", "2", "3", "4"))
        val r = roles(logical, MAIN, UW, TELE, PERI)
        assertEquals(Role.LOGICAL, r["9"]!!.role)
        assertFalse(r["9"]!!.role in USABLE_ROLES)
        assertEquals(Role.MAIN, r["0"]!!.role)
        assertEquals(Role.PERISCOPE, r["4"]!!.role)
    }

    @Test fun telephotoMissing() {
        val r = roles(MAIN, FRONT, UW)
        assertTrue(r.values.none { it.role == Role.TELE_3X })
    }

    @Test fun ambiguousFocalIsLowConfidenceUnknown() {
        val odd = cam("7", focal = 15f, w = 4.4f, h = 3.3f) // ratio ~ 5.7: between tele and periscope
        val r = roles(MAIN, odd)
        assertEquals(Confidence.LOW, r["7"]!!.confidence)
        assertEquals(Role.UNKNOWN, r["7"]!!.role)
    }

    @Test fun duplicateRoleClaimsDemoted() {
        val tele2 = TELE.copy(id = "9")
        val r = roles(MAIN, TELE, tele2)
        assertEquals(Confidence.LOW, r["3"]!!.confidence); assertEquals(Confidence.LOW, r["9"]!!.confidence)
    }

    @Test fun unreadableIsUnknown() {
        val r = roles(MAIN, TELE.copy(readError = "boom"))
        assertEquals(Role.UNKNOWN, r["3"]!!.role)
    }

    @Test fun classificationIgnoresIds() {
        val shuffled = listOf(MAIN.copy(id = "9"), UW.copy(id = "5"), TELE.copy(id = "0"), PERI.copy(id = "2"))
        val r = Classifier.classify(shuffled).associate { it.id to it.role }
        assertEquals(Role.MAIN, r["9"]); assertEquals(Role.ULTRA_WIDE, r["5"])
        assertEquals(Role.TELE_3X, r["0"]); assertEquals(Role.PERISCOPE, r["2"])
    }
}
