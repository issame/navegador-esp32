package es.navegador.esp32

/**
 * Acorta las indicaciones para que quepan en la pantalla redonda sin perder la calle, y
 * deduce el tipo de maniobra para que la placa dibuje una flecha vectorial nítida.
 *
 *  - **acción**: "En la rotonda, toma la primera salida" → "1.ª salida",
 *    "Gira a la izquierda" → "Izq.", "Utiliza el carril izquierdo…" → "Carril izq."…
 *    (la flecha ya muestra el tipo de maniobra);
 *  - **destino**: "… en dirección Avinguda del Paral·lel" → "Av. Paral·lel";
 *  - **tipos de vía** abreviados (castellano y catalán): Av., C/, Pl., Pg., Ctra., Rda.,
 *    Trav., Rbla., Cam., Gta.; y sin "de", "del", "d'"… detrás.
 *  - **tipo de maniobra** (códigos como los de OsmAnd): TL, TR, TSLL, TSLR, TSHL, TSHR,
 *    KL, KR, C, TU, EXITL, EXITR, RNDB1…RNDB8 (salida de rotonda), RNDBX (salir de la
 *    rotonda). Vacío si no se reconoce: la placa usa entonces la flecha de la app.
 *
 * Si una frase no encaja en ningún patrón, solo se abrevian los tipos de vía.
 * No depende de Android para poder probarlo con tests normales (ver InstructionShortenerTest).
 */
object InstructionShortener {

    /** Indicación acortada. */
    data class Parts(
        /** Qué hacer: "Salida 21", "1.ª salida", "Izq."… */
        val action: String,
        /** Hacia dónde: "Ciutat Vella", "Av. Paral·lel"… (puede estar vacío). */
        val destination: String = "",
        /** Tipo de maniobra para la flecha vectorial, o "" si no se reconoce. */
        val maneuver: String = "",
    ) {
        /** Una sola línea para el registro y la app: "Salida 21 · Ciutat Vella". */
        val joined: String get() = if (destination.isEmpty()) action else "$action · $destination"

        /** Formato que se envía a la placa: acción y destino separados por un salto de línea. */
        val forBoard: String get() = if (destination.isEmpty()) action else "$action\n$destination"
    }

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

    /** Dentro del destino, "en dirección" y "hacia" separan partes: "B-10 · Llobregat". */
    private val INNER_DIRECTION = Regex("\\s+(?:en dirección a|en dirección|hacia)\\s+", RegexOption.IGNORE_CASE)

    private val ORDINALS = mapOf(
        "primera" to 1, "segunda" to 2, "tercera" to 3, "cuarta" to 4,
        "quinta" to 5, "sexta" to 6, "séptima" to 7, "octava" to 8,
    )

    /** Palabras que unen la acción con el destino ("hacia", "y continúa por", "en dirección"…). */
    private const val CONNECTOR =
        "(?:y continúa por|y sigue por|y continúa|y sigue|en dirección a|en dirección|hacia el|hacia la|hacia|" +
            "por el|por la|por|en el|en la|en|al|a la|a|onto|to|on)"

    /** Destino opcional al final: " hacia Calle Mayor". Grupo con nombre "destino". */
    private const val DESTINATION = "(?:\\s+$CONNECTOR\\s+(?<destino>.+))?"

    private fun pattern(action: String) = Regex("^$action$DESTINATION$", RegexOption.IGNORE_CASE)

    // En la rotonda / En Pl. d'Espanya, toma la cuarta salida (y continúa por …)
    private val ROUNDABOUT_EXIT = pattern("En (?<lugar>.+?),\\s*toma la (?<ordinal>\\p{L}+) salida")
    private val LEAVE_ROUNDABOUT = pattern("Sal de la rotonda")
    // Gira (ligeramente | bruscamente) a la izquierda / derecha
    private val TURN = pattern("(?:Gira|Gire|Gíra|Turn)\\s+(?:(?<matiz>ligeramente|bruscamente)\\s+)?a la (?<lado>izquierda|derecha)")
    private val KEEP_SIDE = pattern("(?:Mantente|Manténgase|Mantenerse)\\s+a la (?<lado>izquierda|derecha)")
    private val TAKE_EXIT = pattern("Toma la salida(?:\\s+(?<numero>\\d+[A-Za-z]?))?(?:\\s+a la (?<lado>izquierda|derecha))?")
    private val TAKE_RAMP = pattern("Toma la rampa(?:\\s+a la (?<lado>izquierda|derecha))?")
    private val U_TURN = pattern("(?:Cambia de sentido|Cambie de sentido|Haz un cambio de sentido|Da la vuelta)")
    private val STRAIGHT = pattern("(?:Continúa|Continúe|Sigue|Siga)(?:\\s+recto)?")
    private val HEAD = pattern("(?:Dirígete|Diríjase)")
    private val MERGE = pattern("(?:Incorpórate|Incorpórese)")
    // Utiliza el carril izquierdo para tomar la rampa B-10/Ronda Litoral en dirección Llobregat
    private val USE_LANE = Regex(
        "^Utiliza (?:el|los) (?:dos )?carril(?:es)? (?<lado>izquierdos?|derechos?|centrales|central)" +
            "(?: para (?:tomar|seguir por|continuar por|salir por|incorporarte a)" +
            "(?: la rampa| la salida| el desvío)?)?(?:\\s+(?<destino>.+))?$",
        RegexOption.IGNORE_CASE,
    )

    /** Acorta una indicación completa y la devuelve en una línea ("acción · destino"). */
    fun shorten(instruction: String): String = shortenParts(instruction).joined

    /**
     * Acorta una indicación completa (puede tener varias líneas: la que tiene la acción manda;
     * las demás se añaden al destino).
     */
    fun shortenParts(instruction: String): Parts {
        val lines = instruction.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val recognised = lines.firstNotNullOfOrNull { line -> recognise(line)?.let { line to it } }
        if (recognised == null) {
            return Parts(action = lines.joinToString(" · ") { abbreviateStreets(it) })
        }
        val (actionLine, parts) = recognised
        val others = lines.filter { it != actionLine }.map(::abbreviateStreets)
        val destination = (listOf(parts.destination) + others).filter { it.isNotEmpty() }.joinToString(" · ")
        return parts.copy(destination = destination)
    }

    /** Reconoce la frase y devuelve la acción acortada, el destino ya abreviado y el tipo de maniobra. */
    private fun recognise(line: String): Parts? {
        fun destinationOf(match: MatchResult) = match.groups["destino"]?.value?.let(::cleanDestination).orEmpty()

        ROUNDABOUT_EXIT.matchEntire(line)?.let { match ->
            val exitNumber = ORDINALS[match.groups["ordinal"]!!.value.lowercase()] ?: return null
            val place = match.groups["lugar"]!!.value
            val exitText = "$exitNumber.ª salida"
            val action = if (place.equals("la rotonda", ignoreCase = true)) exitText
            else "${abbreviateStreets(place)}: $exitText"
            return Parts(action, destinationOf(match), "RNDB$exitNumber")
        }
        LEAVE_ROUNDABOUT.matchEntire(line)?.let { match ->
            return Parts("Sal de la rotonda", destinationOf(match), "RNDBX")
        }
        TURN.matchEntire(line)?.let { match ->
            val left = isLeft(match.groups["lado"]!!.value)
            val side = sideAbbreviation(left)
            val (action, maneuver) = when (match.groups["matiz"]?.value?.lowercase()) {
                "ligeramente" -> "Lig. $side" to if (left) "TSLL" else "TSLR"
                "bruscamente" -> "Brusco $side" to if (left) "TSHL" else "TSHR"
                else -> side.replaceFirstChar { it.uppercase() } to if (left) "TL" else "TR"
            }
            return Parts(action, destinationOf(match), maneuver)
        }
        KEEP_SIDE.matchEntire(line)?.let { match ->
            val left = isLeft(match.groups["lado"]!!.value)
            return Parts("Mantente ${sideAbbreviation(left)}", destinationOf(match), if (left) "KL" else "KR")
        }
        USE_LANE.matchEntire(line)?.let { match ->
            val lane = match.groups["lado"]!!.value.lowercase()
            val (action, maneuver) = when {
                lane.startsWith("izquierd") -> "Carril izq." to "KL"
                lane.startsWith("derech") -> "Carril dch." to "KR"
                else -> "Carril central" to "C"
            }
            return Parts(action, destinationOf(match), maneuver)
        }
        TAKE_EXIT.matchEntire(line)?.let { match ->
            val number = match.groups["numero"]?.value?.let { " $it" }.orEmpty()
            val sideWord = match.groups["lado"]?.value
            val left = sideWord != null && isLeft(sideWord)
            val side = sideWord?.let { " " + sideAbbreviation(left) }.orEmpty()
            return Parts("Salida$number$side", destinationOf(match), if (left) "EXITL" else "EXITR")
        }
        TAKE_RAMP.matchEntire(line)?.let { match ->
            val sideWord = match.groups["lado"]?.value
            val left = sideWord != null && isLeft(sideWord)
            val side = sideWord?.let { " " + sideAbbreviation(left) }.orEmpty()
            return Parts("Rampa$side", destinationOf(match), if (left) "EXITL" else "EXITR")
        }
        U_TURN.matchEntire(line)?.let { match ->
            return Parts("Cambio de sentido", destinationOf(match), "TU")
        }
        STRAIGHT.matchEntire(line)?.let { match ->
            return Parts("Recto", destinationOf(match), "C")
        }
        HEAD.matchEntire(line)?.let { match ->
            return Parts("Dirígete", destinationOf(match), "C")
        }
        MERGE.matchEntire(line)?.let { match ->
            return Parts("Incorpórate", destinationOf(match), "") // sin flecha propia: la de la app
        }
        return null
    }

    private fun isLeft(side: String) = side.lowercase().startsWith("izquierd")

    /** "izquierda" → "izq.", "derecha" → "dch." */
    private fun sideAbbreviation(left: Boolean) = if (left) "izq." else "dch."

    /** Abrevia el destino y separa con " · " sus partes ("B-10/Rda. Litoral · Llobregat"). */
    private fun cleanDestination(destination: String): String =
        abbreviateStreets(destination.replace(INNER_DIRECTION, " · "))

    /** Abrevia los tipos de vía y quita las partículas que van detrás. */
    fun abbreviateStreets(text: String): String {
        var result = text
        for ((fullName, abbreviation) in STREET_TYPES) result = result.replace(fullName, abbreviation)
        return result.replace(PARTICLE_AFTER_STREET_TYPE) { "${it.groupValues[1]} " }.trim()
    }
}
