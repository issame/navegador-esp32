package es.navegador.esp32

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionShortenerTest {

    private fun shorten(text: String) = InstructionShortener.shorten(text)

    // ------------------------------------------------------------ ejemplos reales de los registros

    @Test
    fun mapsRoundabout_firstExit() {
        assertEquals(
            "1.ª salida · Av. Paral·lel",
            shorten("En la rotonda, toma la primera salida en dirección Avinguda del Paral·lel"),
        )
    }

    @Test
    fun mapsNamedRoundabout_fourthExit() {
        assertEquals(
            "Pl. Espanya: 4.ª salida · Gran Via Corts Catalanes/C-31",
            shorten("En Pl. d'Espanya, toma la cuarta salida hacia Gran Via de les Corts Catalanes/C-31"),
        )
    }

    @Test
    fun mapsLeaveRoundabout() {
        assertEquals("Sal de la rotonda · Av. Paral·lel", shorten("Sal de la rotonda en Avinguda del Paral·lel"))
    }

    @Test
    fun osmandTurnLeft() {
        assertEquals("Izq. · C/ Sagunt", shorten("Gira a la izquierda en Carrer de Sagunt"))
    }

    @Test
    fun mapsHeadTowards() {
        assertEquals("Dirígete · C/ Jocs Florals", shorten("Dirígete hacia Carrer dels Jocs Florals"))
    }

    // ------------------------------------------------------------ acciones

    @Test
    fun turnsWithoutStreet() {
        assertEquals("Izq.", shorten("Gira a la izquierda"))
        assertEquals("Dch.", shorten("Gira a la derecha"))
        assertEquals("Izq.", shorten("Gire a la izquierda"))
    }

    @Test
    fun slightAndSharpTurns() {
        assertEquals("Lig. izq. · C/ Mayor", shorten("Gira ligeramente a la izquierda hacia Calle Mayor"))
        assertEquals("Brusco dch.", shorten("Gira bruscamente a la derecha"))
    }

    @Test
    fun keepSide_exit_uTurn_straight() {
        assertEquals("Mantente izq. · A-2", shorten("Mantente a la izquierda en la A-2"))
        assertEquals("Salida 5 · Sant Cugat", shorten("Toma la salida 5 hacia Sant Cugat"))
        assertEquals("Salida dch.", shorten("Toma la salida a la derecha"))
        assertEquals("Cambio de sentido", shorten("Haz un cambio de sentido"))
        assertEquals("Recto · Gran Via Corts Catalanes", shorten("Continúa recto por Gran Via de les Corts Catalanes"))
    }

    // ------------------------------------------------------------ vías y casos generales

    @Test
    fun streetTypesInUnknownSentence() {
        assertEquals("Llegarás a Pl. Catalunya", shorten("Llegarás a Plaça de Catalunya"))
        assertEquals("Pg. Gràcia", shorten("Passeig de Gràcia"))
        assertEquals("Ctra. Sants", shorten("Carretera de Sants"))
    }

    @Test
    fun wordsThatContainStreetTypesAreNotTouched() {
        // "Rondalla" contiene "Ronda" y "Calleja" contiene "Calle": no se abrevian.
        assertEquals("Rondalla · Calleja", shorten("Rondalla\nCalleja"))
    }

    @Test
    fun severalLines_actionFirstAndRestAsDestination() {
        assertEquals("Dch. · C/ Mayor", shorten("Calle Mayor\nGira a la derecha"))
    }

    @Test
    fun unknownOrdinal_fallsBackToStreetAbbreviation() {
        assertEquals(
            "En la rotonda, toma la novena salida hacia Av. Diagonal",
            shorten("En la rotonda, toma la novena salida hacia Avinguda Diagonal"),
        )
    }

    // ------------------------------------------------------------ ruta real de Google Maps (registro)

    private fun parts(text: String) = InstructionShortener.shortenParts(text)

    @Test
    fun realRoute_roundaboutWithContinueBy() {
        val p = parts("En Pl. dels Voluntaris Olimpics, toma la segunda salida y continúa por Carrer de Salvador Espriu")
        assertEquals("Pl. Voluntaris Olimpics: 2.ª salida", p.action)
        assertEquals("C/ Salvador Espriu", p.destination)
        assertEquals("RNDB2", p.maneuver)
    }

    @Test
    fun realRoute_useLeftLaneForRamp() {
        val p = parts("Utiliza el carril izquierdo para tomar la rampa B-10/Ronda Litoral en dirección Llobregat")
        assertEquals("Carril izq.", p.action)
        assertEquals("B-10/Rda. Litoral · Llobregat", p.destination)
        assertEquals("KL", p.maneuver)
    }

    @Test
    fun realRoute_motorwayExit() {
        val p = parts("Toma la salida 21 hacia Ciutat Vella")
        assertEquals("Salida 21", p.action)
        assertEquals("Ciutat Vella", p.destination)
        assertEquals("EXITR", p.maneuver)
        assertEquals("Salida 21\nCiutat Vella", p.forBoard)
    }

    @Test
    fun realRoute_otherPhrasesAndManeuvers() {
        assertEquals(InstructionShortener.Parts("1.ª salida", "Av. Paral·lel", "RNDB1"),
            parts("En la rotonda, toma la primera salida en dirección Avinguda del Paral·lel"))
        assertEquals(InstructionShortener.Parts("Pl. Espanya: 4.ª salida", "Gran Via Corts Catalanes/C-31", "RNDB4"),
            parts("En Pl. d'Espanya, toma la cuarta salida hacia Gran Via de les Corts Catalanes/C-31"))
        assertEquals(InstructionShortener.Parts("Sal de la rotonda", "C/ Salvador Espriu", "RNDBX"),
            parts("Sal de la rotonda en Carrer de Salvador Espriu"))
        assertEquals(InstructionShortener.Parts("Dch.", "C/ Moianès", "TR"),
            parts("Gira a la derecha hacia Carrer del Moianès"))
        assertEquals(InstructionShortener.Parts("Dirígete", "C/ Toledo", "C"),
            parts("Dirígete hacia Carrer de Toledo"))
        assertEquals(InstructionShortener.Parts("Iniciando navegación…", "", ""),
            parts("Iniciando navegación…"))
    }

    @Test
    fun maneuverCodes_forTurns() {
        assertEquals("TL", parts("Gira a la izquierda").maneuver)
        assertEquals("TSLR", parts("Gira ligeramente a la derecha").maneuver)
        assertEquals("TSHL", parts("Gira bruscamente a la izquierda").maneuver)
        assertEquals("KR", parts("Mantente a la derecha").maneuver)
        assertEquals("TU", parts("Haz un cambio de sentido").maneuver)
        assertEquals("", parts("Incorpórate a la B-20").maneuver)
    }
}
