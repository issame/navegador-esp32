package es.navegador.esp32

/**
 * Extrae los datos de navegación de los textos de la notificación de Google Maps.
 *
 * El formato cambia según la versión y el idioma de Maps, así que no se depende de
 * posiciones fijas sino de patrones:
 *  - **distancia a la maniobra**: al principio de un texto ("250 m", "En 1,2 km, gira…").
 *  - **línea resumen**: contiene la hora de llegada y/o tiempo y distancia restantes
 *    ("19 min · 8,4 km · 14:51").
 *  - **indicación**: el resto del título y del texto ("Gira a la izquierda hacia…").
 *
 * No depende de Android para poder probarla con tests normales (ver MapsParserTest).
 */
object MapsParser {

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

    /** Espacios especiales que Maps usa en horas y distancias ("14 h", "2:51 p. m."). */
    private val SPECIAL_SPACES = Regex("[\\u00A0\\u2007\\u2009\\u202F]")
    private val REPEATED_WHITESPACE = Regex("\\s+")

    /** Un número con decimales opcionales: "250", "8,4", "1.2". */
    private const val NUMBER = "\\d+(?:[.,]\\d+)?"

    /** Unidad de distancia que no va seguida de otra letra (para no confundir "m" con "min"). */
    private const val DISTANCE_UNIT = "(?:km|m|mi|ft|yd)(?!\\p{L})"

    /** Distancia al principio del texto, con "en"/"a"/"in" delante opcional. */
    private val MANEUVER_DISTANCE =
        Regex("^(?:(?:en|a|in)\\s+)?($NUMBER\\s?$DISTANCE_UNIT)", RegexOption.IGNORE_CASE)

    /** Cualquier distancia dentro de un texto. */
    private val ANY_DISTANCE = Regex("$NUMBER\\s?$DISTANCE_UNIT", RegexOption.IGNORE_CASE)

    /** Duración: "19 min", "1 h", "1 h 5 min". */
    private val DURATION = Regex(
        "\\d+\\s?h(?:\\s?\\d+\\s?min)?(?!\\p{L})|\\d+\\s?min(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    /** Hora: "14:51" o "2:51 p. m.". */
    private val CLOCK_TIME = Regex("\\d{1,2}:\\d{2}(?:\\s?[ap]\\.?\\s?m\\.?)?", RegexOption.IGNORE_CASE)

    /** Caracteres que separan la distancia de la indicación ("300 m · Gira…"). */
    private val SEPARATORS = setOf('·', '-', '–', '—', ',', ':', '•', ' ')

    /** Normaliza espacios y quita los de los extremos. */
    fun clean(raw: String?): String =
        raw?.replace(SPECIAL_SPACES, " ")?.replace(REPEATED_WHITESPACE, " ")?.trim() ?: ""

    fun parse(texts: NotificationTexts): NavInfo {
        val title = clean(texts.title)
        val text = clean(texts.text)
        val otherTexts = (listOf(clean(texts.subText), clean(texts.bigText), clean(texts.infoText)) +
            texts.extra.map(::clean)).filter { it.isNotEmpty() }
        val allTexts = (listOf(title, text) + otherTexts).filter { it.isNotEmpty() }.distinct()

        // 1. Línea resumen: la que tiene hora de llegada junto con tiempo o distancia,
        //    o, si no la hay, la que tiene tiempo y distancia sin ser una maniobra.
        val summaryLine = allTexts.firstOrNull {
            CLOCK_TIME.containsMatchIn(it) && (DURATION.containsMatchIn(it) || ANY_DISTANCE.containsMatchIn(it))
        } ?: allTexts.firstOrNull {
            DURATION.containsMatchIn(it) && ANY_DISTANCE.containsMatchIn(it) && !MANEUVER_DISTANCE.containsMatchIn(it)
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
                val match = MANEUVER_DISTANCE.find(candidate)
                if (match != null && maneuverDistance.isEmpty()) {
                    maneuverDistance = match.groupValues[1]
                    // Lo que va detrás de la distancia es parte de la indicación.
                    val rest = candidate.substring(match.range.last + 1).trim { it in SEPARATORS }
                    if (rest.isNotEmpty()) instructionParts += rest
                } else if (candidate != maneuverDistance) {
                    instructionParts += candidate
                }
            }
        }
        collectFrom(listOf(title, text).filter { it.isNotEmpty() })

        // 3. Si el título y el texto no traían nada, se usan los demás textos.
        if (instructionParts.isEmpty() && maneuverDistance.isEmpty()) collectFrom(otherTexts)

        val instruction = instructionParts.distinct().joinToString("\n")
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
