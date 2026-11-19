package es.navegador.esp32

/**
 * Extrae los datos de navegación de los textos de la notificación de Google Maps.
 *
 * El formato cambia según la versión y el idioma de Maps, así que se busca por patrones:
 *  - distancia a la maniobra al principio de un texto ("250 m", "En 1,2 km …")
 *  - una línea resumen con tiempo, distancia y/o hora de llegada ("19 min · 8,4 km · 14:51")
 *  - el resto es la indicación.
 */
object MapsParser {

    data class Fields(
        val title: String? = null,
        val text: String? = null,
        val subText: String? = null,
        val bigText: String? = null,
        val infoText: String? = null,
        val extra: List<String> = emptyList(),
    ) {
        fun describe(): String = buildList {
            title?.let { add("título=\"$it\"") }
            text?.let { add("texto=\"$it\"") }
            subText?.let { add("sub=\"$it\"") }
            bigText?.let { if (it != text) add("largo=\"$it\"") }
            infoText?.let { add("info=\"$it\"") }
            if (extra.isNotEmpty()) add("otros=${extra.joinToString(" | ", "[", "]")}")
        }.joinToString(", ")
    }

    private val ODD_SPACES = Regex("[\\u00A0\\u2007\\u2009\\u202F]")
    private val WHITESPACE = Regex("\\s+")
    private const val NUM = "\\d+(?:[.,]\\d+)?"
    private const val UNIT = "(?:km|m|mi|ft|yd)(?!\\p{L})"
    private val MANEUVER = Regex("^(?:(?:en|a|in)\\s+)?($NUM\\s?$UNIT)", RegexOption.IGNORE_CASE)
    private val DISTANCE = Regex("$NUM\\s?$UNIT", RegexOption.IGNORE_CASE)
    private val DURATION = Regex(
        "\\d+\\s?h(?:\\s?\\d+\\s?min)?(?!\\p{L})|\\d+\\s?min(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    private val ETA = Regex("\\d{1,2}:\\d{2}(?:\\s?[ap]\\.?\\s?m\\.?)?", RegexOption.IGNORE_CASE)
    private val SEPARATORS = setOf('·', '-', '–', '—', ',', ':', '•', ' ')

    fun clean(s: String?): String =
        s?.replace(ODD_SPACES, " ")?.replace(WHITESPACE, " ")?.trim() ?: ""

    fun parse(f: Fields): NavInfo {
        val title = clean(f.title)
        val text = clean(f.text)
        val others = (listOf(clean(f.subText), clean(f.bigText), clean(f.infoText)) + f.extra.map(::clean))
            .filter { it.isNotEmpty() }
        val all = (listOf(title, text) + others).filter { it.isNotEmpty() }.distinct()

        val summary = all.firstOrNull {
            ETA.containsMatchIn(it) && (DURATION.containsMatchIn(it) || DISTANCE.containsMatchIn(it))
        } ?: all.firstOrNull {
            DURATION.containsMatchIn(it) && DISTANCE.containsMatchIn(it) && !MANEUVER.containsMatchIn(it)
        }

        var duration = ""
        var distance = ""
        var eta = ""
        if (summary != null) {
            duration = DURATION.find(summary)?.value.orEmpty()
            eta = ETA.find(summary)?.value.orEmpty()
            distance = DISTANCE.find(summary)?.value.orEmpty()
        }

        var maneuver = ""
        val instructions = mutableListOf<String>()
        fun take(pool: List<String>) {
            for (s in pool) {
                if (s == summary) continue
                val m = MANEUVER.find(s)
                if (m != null && maneuver.isEmpty()) {
                    maneuver = m.groupValues[1]
                    val rest = s.substring(m.range.last + 1).trim { it in SEPARATORS }
                    if (rest.isNotEmpty()) instructions += rest
                } else if (s != maneuver) {
                    instructions += s
                }
            }
        }
        take(listOf(title, text).filter { it.isNotEmpty() })
        if (instructions.isEmpty() && maneuver.isEmpty()) take(others)

        val instruction = instructions.distinct().joinToString("\n")
        return NavInfo(
            active = maneuver.isNotEmpty() || instruction.isNotEmpty(),
            maneuverDistance = maneuver,
            instruction = instruction,
            duration = duration,
            distance = distance,
            eta = eta,
        )
    }
}
