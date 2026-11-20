package es.navegador.esp32

/**
 * Decide si una ruta que acaba de terminar ha sido una **llegada** al destino o una
 * cancelación. Las apps de navegación quitan su notificación en los dos casos, así que se
 * mira la última navegación recibida:
 *  - la maniobra estaba a [MAX_ARRIVAL_METERS] metros o menos, o
 *  - la indicación hablaba del destino ("Llegarás a tu destino", "Destino", "Has llegado"…).
 *
 * No depende de Android para poder probarlo con tests normales (ver ArrivalDetectorTest).
 */
object ArrivalDetector {
    const val MAX_ARRIVAL_METERS = 50.0

    private val DESTINATION_WORDS = Regex(
        "destino|has llegado|llegarás|llegada a|destination|arrived|arrive",
        RegexOption.IGNORE_CASE,
    )

    private val DISTANCE = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s?(km|mi|ft|yd|m)(?!\\p{L})", RegexOption.IGNORE_CASE)

    fun isArrival(lastNavigation: NavInfo): Boolean {
        if (!lastNavigation.active || lastNavigation.arrived) return false
        if (DESTINATION_WORDS.containsMatchIn(lastNavigation.instruction)) return true
        val meters = metersOf(lastNavigation.maneuverDistance) ?: return false
        return meters <= MAX_ARRIVAL_METERS
    }

    /** "250 m" → 250, "1,2 km" → 1200, "0.1 mi" → 160,9… o null si no es una distancia. */
    fun metersOf(text: String): Double? {
        val match = DISTANCE.find(text) ?: return null
        val unit = match.groupValues[2].lowercase()
        val rawNumber = match.groupValues[1]
        // En km y millas el separador es decimal; en m, pies y yardas solo puede ser de miles.
        val number = if (unit == "km" || unit == "mi") rawNumber.replace(',', '.')
        else rawNumber.replace(".", "").replace(",", "")
        val value = number.toDoubleOrNull() ?: return null
        return when (unit) {
            "km" -> value * 1000
            "mi" -> value * 1609.34
            "ft" -> value * 0.3048
            "yd" -> value * 0.9144
            else -> value
        }
    }
}
