package es.navegador.esp32

/**
 * Acorta las indicaciones para que quepan en la pantalla redonda sin perder la calle:
 *
 *  - **acción**: "En la rotonda, toma la primera salida" → "1.ª salida",
 *    "Gira a la izquierda" → "Izq.", "Gira ligeramente a la derecha" → "Lig. dch."…
 *    (la flecha ya muestra el tipo de maniobra);
 *  - **destino** separado por " · ": "… en dirección Avinguda del Paral·lel" → "… · Av. Paral·lel";
 *  - **tipos de vía** abreviados (castellano y catalán): Av., C/, Pl., Pg., Ctra., Rda.,
 *    Trav., Rbla., Cam., Gta.; y sin "de", "del", "d'"… detrás.
 *
 * Si una frase no encaja en ningún patrón, solo se abrevian los tipos de vía.
 * No depende de Android para poder probarlo con tests normales (ver InstructionShortenerTest).
 */
object InstructionShortener {

    /** Tipos de vía y su abreviatura. */
    private val STREET_TYPES = listOf(
        "Avenida" to "Av.", "Avinguda" to "Av.",
        "Calle" to "C/", "Carrer" to "C/",
        "Plaza" to "Pl.", "Plaça" to "Pl.",
        "Paseo" to "Pg.", "Passeig" to "Pg.",
        "Carretera" to "Ctra.",
        "Ronda" to "Rda.",
        "Travesía" to "Trav.", "Travessera" to "Trav.",
        "Rambla" to "Rbla.",
        "Camino" to "Cam.", "Camí" to "Cam.",
        "Glorieta" to "Gta.",
    ).map { (full, short) -> Regex("(?<!\\p{L})$full(?!\\p{L})") to short }

    /** Tras un tipo de vía (abreviado) o "Gran Via", se quitan las partículas "de", "del", "d'"… */
    private val PARTICLE_AFTER_STREET_TYPE = Regex(
        "(Av\\.|C/|Pl\\.|Pg\\.|Ctra\\.|Rda\\.|Trav\\.|Rbla\\.|Cam\\.|Gta\\.|Gran V[ií]a)\\s+" +
            "(?:de las|de los|de les|de la|dels|del|de l['’]|de|d['’])\\s*",
    )

    private val ORDINALS = mapOf(
        "primera" to "1.ª", "segunda" to "2.ª", "tercera" to "3.ª", "cuarta" to "4.ª",
        "quinta" to "5.ª", "sexta" to "6.ª", "séptima" to "7.ª", "octava" to "8.ª",
    )

    /** Palabras que unen la acción con el destino ("hacia", "en dirección", "por"…). */
    private const val CONNECTOR =
        "(?:en dirección a|en dirección|hacia el|hacia la|hacia|por el|por la|por|en el|en la|en|al|a la|a|onto|to|on)"

    /** Destino opcional al final: " hacia Calle Mayor". Grupo con nombre "destino". */
    private const val DESTINATION = "(?:\\s+$CONNECTOR\\s+(?<destino>.+))?"

    private fun pattern(action: String) = Regex("^$action$DESTINATION$", RegexOption.IGNORE_CASE)

    // En la rotonda / En Pl. d'Espanya, toma la cuarta salida
    private val ROUNDABOUT_EXIT = pattern("En (?<lugar>.+?),\\s*toma la (?<ordinal>\\p{L}+) salida")
    private val LEAVE_ROUNDABOUT = pattern("Sal de la rotonda")
    // Gira (ligeramente | bruscamente) a la izquierda / derecha
    private val TURN = pattern("(?:Gira|Gire|Gíra|Turn)\\s+(?:(?<matiz>ligeramente|bruscamente)\\s+)?a la (?<lado>izquierda|derecha)")
    private val KEEP_SIDE = pattern("(?:Mantente|Manténgase|Mantenerse)\\s+a la (?<lado>izquierda|derecha)")
    private val TAKE_EXIT = pattern("Toma la salida(?:\\s+(?<numero>\\d+[A-Za-z]?))?(?:\\s+a la (?<lado>izquierda|derecha))?")
    private val U_TURN = pattern("(?:Cambia de sentido|Cambie de sentido|Haz un cambio de sentido|Da la vuelta)")
    private val STRAIGHT = pattern("(?:Continúa|Continúe|Sigue|Siga)(?:\\s+recto)?")
    private val HEAD_OR_MERGE = pattern("(?<verbo>Dirígete|Diríjase|Incorpórate|Incorpórese)")

    /** Acorta una indicación completa (puede tener varias líneas). */
    fun shorten(instruction: String): String =
        instruction.split('\n')
            .map { shortenLine(it.trim()) }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    private fun shortenLine(line: String): String {
        if (line.isEmpty()) return line
        val (action, destination) = splitActionAndDestination(line) ?: return abbreviateStreets(line)
        val shortDestination = destination?.let(::abbreviateStreets)
        return if (shortDestination.isNullOrEmpty()) action else "$action · $shortDestination"
    }

    /** Devuelve la acción ya acortada y el destino (sin abreviar), o null si no se reconoce la frase. */
    private fun splitActionAndDestination(line: String): Pair<String, String?>? {
        ROUNDABOUT_EXIT.matchEntire(line)?.let { match ->
            val ordinal = ORDINALS[match.groups["ordinal"]!!.value.lowercase()] ?: return null
            val place = match.groups["lugar"]!!.value
            val action = if (place.equals("la rotonda", ignoreCase = true)) "$ordinal salida"
            else "${abbreviateStreets(place)}: $ordinal salida"
            return action to match.groups["destino"]?.value
        }
        LEAVE_ROUNDABOUT.matchEntire(line)?.let { match ->
            return "Sal de la rotonda" to match.groups["destino"]?.value
        }
        TURN.matchEntire(line)?.let { match ->
            val side = sideAbbreviation(match.groups["lado"]!!.value)
            val action = when (match.groups["matiz"]?.value?.lowercase()) {
                "ligeramente" -> "Lig. $side"
                "bruscamente" -> "Brusco $side"
                else -> side.replaceFirstChar { it.uppercase() }
            }
            return action to match.groups["destino"]?.value
        }
        KEEP_SIDE.matchEntire(line)?.let { match ->
            return "Mantente ${sideAbbreviation(match.groups["lado"]!!.value)}" to match.groups["destino"]?.value
        }
        TAKE_EXIT.matchEntire(line)?.let { match ->
            val number = match.groups["numero"]?.value?.let { " $it" }.orEmpty()
            val side = match.groups["lado"]?.value?.let { " " + sideAbbreviation(it) }.orEmpty()
            return "Salida$number$side" to match.groups["destino"]?.value
        }
        U_TURN.matchEntire(line)?.let { match ->
            return "Cambio de sentido" to match.groups["destino"]?.value
        }
        STRAIGHT.matchEntire(line)?.let { match ->
            return "Recto" to match.groups["destino"]?.value
        }
        HEAD_OR_MERGE.matchEntire(line)?.let { match ->
            val verb = match.groups["verbo"]!!.value.replaceFirstChar { it.uppercase() }
            return verb to match.groups["destino"]?.value
        }
        return null
    }

    /** "izquierda" → "izq.", "derecha" → "dch." */
    private fun sideAbbreviation(side: String) = if (side.lowercase() == "izquierda") "izq." else "dch."

    /** Abrevia los tipos de vía y quita las partículas que van detrás. */
    fun abbreviateStreets(text: String): String {
        var result = text
        for ((fullName, abbreviation) in STREET_TYPES) result = result.replace(fullName, abbreviation)
        return result.replace(PARTICLE_AFTER_STREET_TYPE) { "${it.groupValues[1]} " }.trim()
    }
}
