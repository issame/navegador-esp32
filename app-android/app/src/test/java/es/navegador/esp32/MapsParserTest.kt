package es.navegador.esp32

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsParserTest {

    private fun parse(title: String?, text: String?, sub: String? = null, big: String? = null) =
        MapsParser.parse(MapsParser.Fields(title = title, text = text, subText = sub, bigText = big))

    @Test
    fun distanceTitle_instructionText_summarySub() {
        val n = parse("250 m", "Gira a la izquierda hacia Carrer de la Peña", "19 min · 8,4 km · Llegada: 14:51")
        assertTrue(n.active)
        assertEquals("250 m", n.maneuverDistance)
        assertEquals("Gira a la izquierda hacia Carrer de la Peña", n.instruction)
        assertEquals("19 min", n.duration)
        assertEquals("8,4 km", n.distance)
        assertEquals("14:51", n.eta)
    }

    @Test
    fun distanceAndInstructionInSameText() {
        val n = parse("Calle Mayor", "300 m · Gira a la derecha")
        assertEquals("300 m", n.maneuverDistance)
        assertEquals("Calle Mayor\nGira a la derecha", n.instruction)
    }

    @Test
    fun spanishPrefixEn() {
        val n = parse("En 1,2 km, gira a la derecha", null)
        assertEquals("1,2 km", n.maneuverDistance)
        assertEquals("gira a la derecha", n.instruction)
    }

    @Test
    fun oddSpacesAnd12hEta() {
        val n = parse("400 m", "Toma la salida", "1 h 5 min · 96 km · 2:51 p. m.")
        assertEquals("400 m", n.maneuverDistance)
        assertEquals("1 h 5 min", n.duration)
        assertEquals("96 km", n.distance)
        assertEquals("2:51 p. m.", n.eta)
    }

    @Test
    fun minutesAreNotMeters() {
        val n = parse("Sigue recto", "5 min · 2 km · 10:05")
        assertEquals("", n.maneuverDistance)
        assertEquals("5 min", n.duration)
        assertEquals("2 km", n.distance)
        assertEquals("Sigue recto", n.instruction)
    }

    @Test
    fun locationSharing_hasNoManeuverDistance() {
        val n = parse("Compartiendo tu ubicación", "con 2 personas")
        assertEquals("", n.maneuverDistance)
    }

    @Test
    fun empty_isInactive() {
        assertFalse(parse(null, null).active)
    }

    @Test
    fun remoteViewTextsUsedWhenNoExtras() {
        val n = MapsParser.parse(
            MapsParser.Fields(extra = listOf("150 m", "Incorpórate a la A-2", "12 min · 9 km · 18:20"))
        )
        assertEquals("150 m", n.maneuverDistance)
        assertEquals("Incorpórate a la A-2", n.instruction)
        assertEquals("18:20", n.eta)
    }
}
