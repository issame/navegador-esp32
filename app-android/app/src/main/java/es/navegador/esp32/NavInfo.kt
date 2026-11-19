package es.navegador.esp32

/** Estado de la navegación que se muestra en la pantalla del ESP32. */
data class NavInfo(
    val active: Boolean,
    /** Distancia hasta la siguiente maniobra, p. ej. "250 m". */
    val maneuverDistance: String = "",
    /** Indicación, p. ej. "Gira a la izquierda hacia Calle Mayor". */
    val instruction: String = "",
    /** Tiempo restante, p. ej. "19 min". */
    val duration: String = "",
    /** Distancia restante, p. ej. "8,4 km". */
    val distance: String = "",
    /** Hora de llegada, p. ej. "14:51". */
    val eta: String = "",
    /** Flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes), o null. */
    val icon: ByteArray? = null,
) {
    /** Línea resumen: "Llegada 14:51 · 19 min · 8,4 km". */
    val summary: String
        get() = listOf(
            if (eta.isNotEmpty()) "Llegada $eta" else "",
            duration,
            distance,
        ).filter { it.isNotEmpty() }.joinToString(" · ")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavInfo) return false
        return active == other.active &&
            maneuverDistance == other.maneuverDistance &&
            instruction == other.instruction &&
            duration == other.duration &&
            distance == other.distance &&
            eta == other.eta &&
            iconEquals(icon, other.icon)
    }

    override fun hashCode(): Int {
        var h = active.hashCode()
        h = 31 * h + maneuverDistance.hashCode()
        h = 31 * h + instruction.hashCode()
        h = 31 * h + duration.hashCode()
        h = 31 * h + distance.hashCode()
        h = 31 * h + eta.hashCode()
        h = 31 * h + (icon?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        val INACTIVE = NavInfo(active = false)

        private fun iconEquals(a: ByteArray?, b: ByteArray?): Boolean =
            if (a == null || b == null) a === b else a.contentEquals(b)
    }
}
