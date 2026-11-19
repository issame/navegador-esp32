package es.navegador.esp32

import kotlin.math.abs

/**
 * Conversión de píxeles ARGB (48x48) al formato del firmware: 1 bit por píxel,
 * fila a fila, el bit más alto es el píxel de la izquierda. 1 = blanco.
 */
object IconBits {
    const val SIZE = 48

    fun pack(argb: IntArray): ByteArray? {
        require(argb.size == SIZE * SIZE)
        // Los iconos de Maps suelen ser una flecha sobre fondo transparente: se usa el alfa.
        // Si la imagen es opaca, se compara cada píxel con el color de la esquina (fondo).
        val transparent = argb.count { alpha(it) < 64 }
        val useAlpha = transparent > argb.size / 10
        val background = argb[0]
        val out = ByteArray(SIZE * SIZE / 8)
        var any = false
        for (i in argb.indices) {
            val p = argb[i]
            val on = if (useAlpha) alpha(p) >= 128 else colorDistance(p, background) > 96
            if (on) {
                out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                any = true
            }
        }
        return if (any) out else null
    }

    /** Para la vista previa: blanco sobre negro. */
    fun unpack(bits: ByteArray): IntArray = IntArray(SIZE * SIZE) { i ->
        if (bits[i / 8].toInt() and (0x80 ushr (i % 8)) != 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    private fun alpha(p: Int) = p ushr 24

    private fun colorDistance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
}
