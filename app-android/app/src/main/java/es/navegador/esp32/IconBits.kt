package es.navegador.esp32

import kotlin.math.abs

/**
 * Convierte píxeles ARGB de 48x48 al formato de la flecha que espera el firmware:
 * 1 bit por píxel, fila a fila, y dentro de cada byte el bit más alto es el píxel de
 * la izquierda. Un 1 se pinta blanco y un 0 negro.
 *
 * Esta parte no depende de Android para poder probarla con tests normales.
 */
object IconBits {
    const val SIZE = 48

    /** Por debajo de este alfa un píxel se considera transparente. */
    private const val TRANSPARENT_ALPHA = 64

    /** A partir de este alfa un píxel forma parte de la flecha. */
    private const val VISIBLE_ALPHA = 128

    /** Diferencia de color (suma de R, G y B) a partir de la cual un píxel no es fondo. */
    private const val MIN_COLOR_DIFFERENCE = 96

    /**
     * Empaqueta [argbPixels] (48 x 48) en 288 bytes.
     * Devuelve null si no hay ningún píxel encendido (no tendría sentido enviarlo).
     */
    fun pack(argbPixels: IntArray): ByteArray? {
        require(argbPixels.size == SIZE * SIZE)

        // Los iconos de Maps suelen ser una flecha sobre fondo transparente: se decide por el alfa.
        // Si la imagen es opaca, se compara cada píxel con el color de la esquina, que es el fondo.
        val transparentCount = argbPixels.count { alpha(it) < TRANSPARENT_ALPHA }
        val decideByAlpha = transparentCount > argbPixels.size / 10
        val backgroundColor = argbPixels[0]

        val packed = ByteArray(SIZE * SIZE / 8)
        var anyPixelOn = false
        for (pixelIndex in argbPixels.indices) {
            val pixel = argbPixels[pixelIndex]
            val isOn = if (decideByAlpha) {
                alpha(pixel) >= VISIBLE_ALPHA
            } else {
                colorDifference(pixel, backgroundColor) > MIN_COLOR_DIFFERENCE
            }
            if (isOn) {
                val byteIndex = pixelIndex / 8
                val bitMask = 0x80 ushr (pixelIndex % 8)
                packed[byteIndex] = (packed[byteIndex].toInt() or bitMask).toByte()
                anyPixelOn = true
            }
        }
        return if (anyPixelOn) packed else null
    }

    /** Operación inversa, para la vista previa de la app: blanco sobre negro. */
    fun unpack(packed: ByteArray): IntArray = IntArray(SIZE * SIZE) { pixelIndex ->
        val bitMask = 0x80 ushr (pixelIndex % 8)
        if (packed[pixelIndex / 8].toInt() and bitMask != 0) WHITE else BLACK
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    private fun alpha(argb: Int) = argb ushr 24

    private fun colorDifference(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
}
