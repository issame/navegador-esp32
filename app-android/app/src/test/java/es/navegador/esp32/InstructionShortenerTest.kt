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
    fun severalLinesAreJoinedWithDot() {
        assertEquals("C/ Mayor · Dch.", shorten("Calle Mayor\nGira a la derecha"))
    }

    @Test
    fun unknownOrdinal_fallsBackToStreetAbbreviation() {
        assertEquals(
            "En la rotonda, toma la novena salida hacia Av. Diagonal",
            shorten("En la rotonda, toma la novena salida hacia Avinguda Diagonal"),
        )
    }
}
