package es.navegador.esp32

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    private fun parse(title: String?, text: String?, sub: String? = null, big: String? = null) =
        NotificationParser.parse(NotificationParser.NotificationTexts(title = title, text = text, subText = sub, bigText = big))

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
        val n = NotificationParser.parse(
            NotificationParser.NotificationTexts(extra = listOf("150 m", "Incorpórate a la A-2", "12 min · 9 km · 18:20"))
        )
        assertEquals("150 m", n.maneuverDistance)
        assertEquals("Incorpórate a la A-2", n.instruction)
        assertEquals("18:20", n.eta)
    }

    // ------------------------------------------------------------ OsmAnd y otros formatos

    @Test
    fun distanceAtEndOfInstruction() {
        val n = parse("Gira a la derecha en 300 m", "Calle Mayor")
        assertEquals("300 m", n.maneuverDistance)
        assertEquals("Gira a la derecha\nCalle Mayor", n.instruction)
    }

    @Test
    fun distanceAtEndInEnglish_osmandStyle() {
        val n = parse("Turn left in 1.2 km", "Main Street", "12 min • 5.4 km • 18:20")
        assertEquals("1.2 km", n.maneuverDistance)
        assertEquals("Turn left\nMain Street", n.instruction)
        assertEquals("12 min", n.duration)
        assertEquals("18:20", n.eta)
    }

    @Test
    fun bulletSeparator_osmandStyle() {
        val n = parse("450 m • Gire a la izquierda", "Avinguda Diagonal")
        assertEquals("450 m", n.maneuverDistance)
        assertEquals("Gire a la izquierda\nAvinguda Diagonal", n.instruction)
    }

    @Test
    fun appRunningNotification_hasNoManeuver() {
        val n = parse("La navegación está en marcha", "Toca para abrir")
        assertEquals("", n.maneuverDistance)
    }

    // ------------------------------------------------------------ OsmAnd real (captura)

    @Test
    fun osmandRealNotification_takesStreetFromSecondLine() {
        // Captura real de OsmAnd~: girar a la izquierda en 80 m hacia Carrer de Sagunt;
        // después se recorren 150 m por esa calle hasta el giro siguiente. El título no trae
        // la calle (acaba en "en"); el texto la trae en la primera línea y el resumen en la segunda.
        val n = parse(
            "80 m • Gira a la izquierda en",
            "Gira a la izquierda en Carrer de Sagunt 150 m\n9,0 km • 24 min • 18:45",
        )
        assertEquals("80 m", n.maneuverDistance)
        assertEquals("Gira a la izquierda en Carrer de Sagunt", n.instruction)
        assertEquals("9,0 km", n.distance)
        assertEquals("24 min", n.duration)
        assertEquals("18:45", n.eta)
    }

    @Test
    fun osmandWithStreetInTitle_unrelatedLineIsIgnored() {
        val n = parse(
            "120 m • Gira a la derecha en Carrer Gran",
            "Sigue recto en Avinguda Diagonal 400 m\n3,2 km • 9 min • 19:02",
        )
        assertEquals("120 m", n.maneuverDistance)
        assertEquals("Gira a la derecha en Carrer Gran", n.instruction)
        assertEquals("3,2 km", n.distance)
    }

    @Test
    fun osmandSameTextInBigText_isNotDuplicated() {
        val text = "Gira a la izquierda en Carrer de Sagunt 150 m\n9,0 km • 24 min • 18:45"
        val n = parse("80 m • Gira a la izquierda en", text, big = text)
        assertEquals("Gira a la izquierda en Carrer de Sagunt", n.instruction)
        assertEquals("9,0 km", n.distance)
    }

    @Test
    fun osmandStreetAlreadyInTitle_isNotRepeated() {
        val n = parse(
            "80 m • Gira a la izquierda en Carrer de Sagunt",
            "Gira a la izquierda en Carrer de Sagunt 150 m\n9,0 km • 24 min • 18:45",
        )
        assertEquals("Gira a la izquierda en Carrer de Sagunt", n.instruction)
    }

    @Test
    fun streetWithoutDistanceLine_keepsTitleWithoutLooseEn() {
        val n = parse("80 m • Gira a la izquierda en", "9,0 km • 24 min • 18:45")
        assertEquals("Gira a la izquierda", n.instruction)
    }
}
