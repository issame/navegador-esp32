package es.navegador.esp32

/**
 * Extrae los datos de navegación de los textos de la notificación de una app de
 * navegación (Google Maps, OsmAnd).
 *
 * El formato cambia según la app, su versión y el idioma, así que no se depende de
 * posiciones fijas sino de patrones:
 *  - **distancia a la maniobra**: al principio de un texto ("250 m", "En 1,2 km, gira…")
 *    o al final con "en"/"in" delante ("Gira a la derecha en 300 m").
 *  - **línea resumen**: contiene la hora de llegada y/o tiempo y distancia restantes
 *    ("19 min · 8,4 km · 14:51"). Google Maps a veces solo pone la llegada
 *    ("Llegada: 21:20"), en una línea corta aparte del título.
 *  - **indicación**: el resto del título y del texto ("Gira a la izquierda hacia…").
 *  - **calle de la maniobra**: OsmAnd pone en el título "80 m • Gira a la izquierda en"
 *    (sin calle, si no cabe) y en el texto "Gira a la izquierda en Carrer de Sagunt 150 m":
 *    la misma maniobra con la calle y, al final, lo que se recorre por esa calle hasta el
 *    giro siguiente. Esa línea completa la indicación; su distancia final se descarta.
 *
 * No depende de Android para poder probarla con tests normales (ver NotificationParserTest).
 */
object NotificationParser {

    /** Textos tal como vienen en la notificación. */
    data class NotificationTexts(
        val title: String? = null,
        val text: String? = null,
        val subText: String? = null,
        val bigText: String? = null,
        val infoText: String? = null,
        /** Otros textos: líneas extra o textos leídos de las vistas personalizadas. */
        val extra: List<String> = emptyList(),
    ) {
        /** Resumen legible para el registro de la app. */
        fun describe(): String = buildList {
            title?.let { add("título=\"$it\"") }
            text?.let { add("texto=\"$it\"") }
            subText?.let { add("sub=\"$it\"") }
            bigText?.let { if (it != text) add("largo=\"$it\"") }
            infoText?.let { add("info=\"$it\"") }
            if (extra.isNotEmpty()) add("otros=${extra.joinToString(" | ", "[", "]")}")
        }.joinToString(", ")
    }

    /** Espacios especiales que usan las apps en horas y distancias ("14 h", "2:51 p. m."). */
    private val SPECIAL_SPACES = Regex("[\\u00A0\\u2007\\u2009\\u202F]")
    private val REPEATED_WHITESPACE = Regex("\\s+")

    /** Un número con decimales opcionales: "250", "8,4", "1.2". */
    private const val NUMBER = "\\d+(?:[.,]\\d+)?"

    /** Unidad de distancia que no va seguida de otra letra (para no confundir "m" con "min"). */
    private const val DISTANCE_UNIT = "(?:km|m|mi|ft|yd)(?!\\p{L})"

    /** Distancia al principio del texto, con "en"/"a"/"in" delante opcional. */
    private val MANEUVER_DISTANCE_AT_START =
        Regex("^(?:(?:en|a|in)\\s+)?($NUMBER\\s?$DISTANCE_UNIT)", RegexOption.IGNORE_CASE)

    /** Distancia al final del texto con "en"/"in" delante: "Gira a la derecha en 300 m". */
    private val MANEUVER_DISTANCE_AT_END =
        Regex("\\s+(?:en|in)\\s+($NUMBER\\s?$DISTANCE_UNIT)[.\\s]*$", RegexOption.IGNORE_CASE)

    /** Cualquier distancia dentro de un texto. */
    private val ANY_DISTANCE = Regex("$NUMBER\\s?$DISTANCE_UNIT", RegexOption.IGNORE_CASE)

    /** Duración: "19 min", "1 h", "1 h 5 min". */
    private val DURATION = Regex(
        "\\d+\\s?h(?:\\s?\\d+\\s?min)?(?!\\p{L})|\\d+\\s?min(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    /** Hora: "14:51" o "2:51 p. m.". */
    private val CLOCK_TIME = Regex("\\d{1,2}:\\d{2}(?:\\s?[ap]\\.?\\s?m\\.?)?", RegexOption.IGNORE_CASE)

    /**
     * Preposición suelta al final, que queda cuando la app no conoce la calle
     * (OsmAnd: "Gira a la izquierda en").
     */
    private val TRAILING_PREPOSITION =
        Regex("\\s+(?:en|a|hacia|por|in|on|onto|to|at)$", RegexOption.IGNORE_CASE)

    /** Distancia al final de una línea, sin preposición delante ("…Carrer de Sagunt 150 m"). */
    private val TRAILING_DISTANCE = Regex("\\s*$NUMBER\\s?$DISTANCE_UNIT[.\\s]*$", RegexOption.IGNORE_CASE)

    /** Una línea corta con una hora y poco más es la hora de llegada ("Llegada: 21:20"). */
    private const val MAX_ARRIVAL_ONLY_LINE_LENGTH = 30

    /** Caracteres que separan la distancia de la indicación ("300 m · Gira…"). */
    private val SEPARATORS = setOf('·', '-', '–', '—', ',', ':', '•', ' ')

    /** Normaliza espacios y quita los de los extremos. */
    fun clean(raw: String?): String =
        raw?.replace(SPECIAL_SPACES, " ")?.replace(REPEATED_WHITESPACE, " ")?.trim() ?: ""

    /** Divide un texto en líneas limpias (OsmAnd pone la indicación y el resumen en líneas distintas). */
    private fun lines(raw: String?): List<String> =
        raw?.split('\n')?.map(::clean)?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * Si [line] repite la última parte de la indicación y la alarga (con la calle), la
     * sustituye, sin la distancia del final. Ejemplo: "Gira a la izquierda en" +
     * "Gira a la izquierda en Carrer de Sagunt 150 m" → "Gira a la izquierda en Carrer de Sagunt".
     */
    private fun completeWithStreet(line: String, instructionParts: MutableList<String>) {
        val current = instructionParts.lastOrNull() ?: return
        val withoutDistance = line.replace(TRAILING_DISTANCE, "").trim { it in SEPARATORS }
        if (withoutDistance.length > current.length && withoutDistance.startsWith(current, ignoreCase = true)) {
            instructionParts[instructionParts.lastIndex] = withoutDistance
        }
    }

    fun parse(texts: NotificationTexts): NavInfo {
        val mainLines = (lines(texts.title) + lines(texts.text)).distinct()
        val otherLines = (lines(texts.subText) + lines(texts.bigText) + lines(texts.infoText) +
            texts.extra.flatMap(::lines)).filter { it !in mainLines }.distinct()
        val allLines = mainLines + otherLines

        // 1. Línea resumen, por orden de preferencia:
        //    - hora de llegada junto con tiempo o distancia ("19 min · 8,4 km · 14:51");
        //    - tiempo y distancia sin ser una maniobra ("19 min · 8,4 km");
        //    - solo la hora de llegada, en una línea corta que no sea el título ("Llegada: 21:20").
        val summaryLine = allLines.firstOrNull {
            CLOCK_TIME.containsMatchIn(it) && (DURATION.containsMatchIn(it) || ANY_DISTANCE.containsMatchIn(it))
        } ?: allLines.firstOrNull {
            DURATION.containsMatchIn(it) && ANY_DISTANCE.containsMatchIn(it) &&
                !MANEUVER_DISTANCE_AT_START.containsMatchIn(it) && !MANEUVER_DISTANCE_AT_END.containsMatchIn(it)
        } ?: allLines.firstOrNull {
            it !in lines(texts.title) && CLOCK_TIME.containsMatchIn(it) && it.length <= MAX_ARRIVAL_ONLY_LINE_LENGTH
        }

        val duration = summaryLine?.let { DURATION.find(it)?.value }.orEmpty()
        val remainingDistance = summaryLine?.let { ANY_DISTANCE.find(it)?.value }.orEmpty()
        val arrivalTime = summaryLine?.let { CLOCK_TIME.find(it)?.value }.orEmpty()

        // 2. Distancia a la maniobra e indicación, primero en el título y el texto.
        var maneuverDistance = ""
        val instructionParts = mutableListOf<String>()
        fun collectFrom(candidates: List<String>) {
            for (candidate in candidates) {
                if (candidate == summaryLine) continue
                if (maneuverDistance.isEmpty()) {
                    val atStart = MANEUVER_DISTANCE_AT_START.find(candidate)
                    if (atStart != null) {
                        maneuverDistance = atStart.groupValues[1]
                        // Lo que va detrás de la distancia es parte de la indicación.
                        val rest = candidate.substring(atStart.range.last + 1).trim { it in SEPARATORS }
                        if (rest.isNotEmpty()) instructionParts += rest
                        continue
                    }
                    val atEnd = MANEUVER_DISTANCE_AT_END.find(candidate)
                    if (atEnd != null) {
                        maneuverDistance = atEnd.groupValues[1]
                        // Lo que va delante de "en 300 m" es la indicación.
                        val rest = candidate.substring(0, atEnd.range.first).trim { it in SEPARATORS }
                        if (rest.isNotEmpty()) instructionParts += rest
                        continue
                    }
                } else if (ANY_DISTANCE.containsMatchIn(candidate)) {
                    // Línea con su propia distancia: o completa la indicación con la calle,
                    // o es otra cosa y se descarta para no mezclarla con la maniobra actual.
                    completeWithStreet(candidate, instructionParts)
                    continue
                }
                if (candidate != maneuverDistance) instructionParts += candidate
            }
        }
        collectFrom(mainLines)

        // 3. Si el título y el texto no traían nada, se usan los demás textos. Si sí traían
        //    la maniobra, en los demás textos solo se busca la calle: OsmAnd la pone en el
        //    texto largo ("Gira a la izquierda en Carrer de Sagunt 150 m").
        if (instructionParts.isEmpty() && maneuverDistance.isEmpty()) {
            collectFrom(otherLines)
        } else {
            otherLines
                .filter { it != summaryLine && ANY_DISTANCE.containsMatchIn(it) }
                .forEach { completeWithStreet(it, instructionParts) }
        }

        val instruction = instructionParts
            .map { it.replace(TRAILING_PREPOSITION, "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        return NavInfo(
            active = maneuverDistance.isNotEmpty() || instruction.isNotEmpty(),
            maneuverDistance = maneuverDistance,
            instruction = instruction,
            duration = duration,
            distance = remainingDistance,
            eta = arrivalTime,
        )
    }
}
