package es.navegador.esp32

/** Estado de la navegación que se muestra en la pantalla del ESP32. */
data class NavInfo(
    val active: Boolean,
    /** Distancia hasta la siguiente maniobra, p. ej. "250 m". */
    val maneuverDistance: String = "",
    /**
     * Indicación. Tras pasar por [InstructionShortener] es "acción\ndestino"
     * (p. ej. "Salida 21\nCiutat Vella"): la placa decide cómo repartirla en líneas.
     */
    val instruction: String = "",
    /** Tiempo restante, p. ej. "19 min". */
    val duration: String = "",
    /** Distancia restante, p. ej. "8,4 km". */
    val distance: String = "",
    /** Hora de llegada, p. ej. "14:51". */
    val eta: String = "",
    /** Flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes), o null. */
    val icon: ByteArray? = null,
    /** La ruta ha terminado porque se ha llegado al destino (la placa muestra "Has llegado"). */
    val arrived: Boolean = false,
    /** Tipo de maniobra para la flecha vectorial de la placa (ver [InstructionShortener]), o "". */
    val maneuver: String = "",
) {
    /** Indicación en una sola línea, para mostrarla en la app y en el registro. */
    val instructionOneLine: String get() = instruction.replace("\n", " · ")

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
            iconEquals(icon, other.icon) &&
            arrived == other.arrived &&
            maneuver == other.maneuver
    }

    override fun hashCode(): Int {
        var h = active.hashCode()
        h = 31 * h + maneuverDistance.hashCode()
        h = 31 * h + instruction.hashCode()
        h = 31 * h + duration.hashCode()
        h = 31 * h + distance.hashCode()
        h = 31 * h + eta.hashCode()
        h = 31 * h + arrived.hashCode()
        h = 31 * h + maneuver.hashCode()
        h = 31 * h + (icon?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        val INACTIVE = NavInfo(active = false)

        /** Aviso de llegada al destino. */
        val ARRIVED = NavInfo(active = true, arrived = true)

        private fun iconEquals(a: ByteArray?, b: ByteArray?): Boolean =
            if (a == null || b == null) a === b else a.contentEquals(b)
    }
}
