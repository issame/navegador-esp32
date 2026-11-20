package es.navegador.esp32

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDetectorTest {

    private fun nav(distance: String, instruction: String = "Dch. · C/ Mayor") =
        NavInfo(active = true, maneuverDistance = distance, instruction = instruction)

    @Test
    fun closeToLastManeuver_isArrival() {
        assertTrue(ArrivalDetector.isArrival(nav("30 m")))
        assertTrue(ArrivalDetector.isArrival(nav("50 m")))
    }

    @Test
    fun farFromManeuver_isCancellation() {
        assertFalse(ArrivalDetector.isArrival(nav("250 m")))
        assertFalse(ArrivalDetector.isArrival(nav("1,2 km")))
    }

    @Test
    fun destinationWords_isArrival() {
        assertTrue(ArrivalDetector.isArrival(nav("", "Llegarás a tu destino")))
        assertTrue(ArrivalDetector.isArrival(nav("300 m", "Tu destino está a la derecha")))
        assertTrue(ArrivalDetector.isArrival(nav("", "Has llegado")))
    }

    @Test
    fun inactiveOrAlreadyArrived_isNotArrival() {
        assertFalse(ArrivalDetector.isArrival(NavInfo.INACTIVE))
        assertFalse(ArrivalDetector.isArrival(NavInfo.ARRIVED))
    }

    @Test
    fun metersOf_units() {
        assertEquals(250.0, ArrivalDetector.metersOf("250 m")!!, 0.01)
        assertEquals(1200.0, ArrivalDetector.metersOf("1,2 km")!!, 0.01)
        assertEquals(1200.0, ArrivalDetector.metersOf("1.200 m")!!, 0.01)
        assertEquals(30.48, ArrivalDetector.metersOf("100 ft")!!, 0.01)
        assertNull(ArrivalDetector.metersOf("5 min"))
        assertNull(ArrivalDetector.metersOf(""))
    }
}
