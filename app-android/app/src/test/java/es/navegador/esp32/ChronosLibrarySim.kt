package es.navegador.esp32

/**
 * Copia en Kotlin de cómo ChronosESP32 1.9.1 recibe los datos (onWrite + dataReceived),
 * para comprobar que la placa entenderá exactamente lo que envía la app.
 */
class ChronosLibrarySim {
    private val data = ByteArray(512)
    private var length = 0

    var time: IntArray? = null // seg, min, hora, día, mes, año
    var navActive = false
    var navHasIcon = false
    var navIsNavigation = false
    var navCrc = 0L
    val navStrings = mutableListOf<String>()
    val icon = ByteArray(288)
    val iconParts = mutableListOf<Int>()
    var packets = 0

    private fun u(i: Int) = data[i].toInt() and 0xFF

    /** ChronosESP32::onWrite */
    fun onWrite(p: ByteArray) {
        val len = p.size
        val b0 = p[0].toInt() and 0xFF
        // std::string termina en '\0': en C++ leer p[len] da 0 en vez de fallar.
        val b3 = p.getOrElse(3) { 0 }.toInt() and 0xFF
        if ((b0 == 0xAB || b0 == 0xEA) && (b3 == 0xFE || b3 == 0xFF)) {
            length = (p[1].toInt() and 0xFF) * 256 + (p[2].toInt() and 0xFF) + 3
            p.copyInto(data, 0)
            if (length <= len) dataReceived()
        } else {
            val j = 20 + b0 * 19
            // En C++ lee p[i + 1] para i < len (el último es el terminador de std::string).
            for (i in 0 until len) data[j + i] = if (i + 1 < len) p[i + 1] else 0
            if (length <= len + j - 1) dataReceived()
        }
    }

    private fun dataReceived() {
        packets++
        val len = length
        if (u(0) != 0xAB) return
        when (u(4)) {
            0x93 -> time = intArrayOf(u(13), u(12), u(11), u(10), u(9), u(7) * 256 + u(8))
            0xEE -> if (u(3) == 0xFE) {
                val pos = u(6)
                navCrc = (u(7).toLong() shl 24) or (u(8).toLong() shl 16) or (u(9).toLong() shl 8) or u(10).toLong()
                for (i in 0 until 96) icon[i + 96 * pos] = data[11 + i]
                iconParts += pos
            }
            0xEF -> if (u(3) == 0xFE) {
                when (u(5)) {
                    0x00 -> { navActive = false; navStrings.clear() }
                    0x80 -> {
                        navActive = true
                        navHasIcon = u(6) == 1
                        navIsNavigation = u(7) == 1
                        navCrc = (u(8).toLong() shl 24) or (u(9).toLong() shl 16) or (u(10).toLong() shl 8) or u(11).toLong()
                        navStrings.clear()
                        var i = 12
                        repeat(6) {
                            val bytes = mutableListOf<Byte>()
                            while (i < len && data[i].toInt() != 0) { bytes += data[i]; i++ }
                            i++
                            navStrings += String(bytes.toByteArray(), Charsets.UTF_8)
                        }
                    }
                }
            }
        }
    }
}
