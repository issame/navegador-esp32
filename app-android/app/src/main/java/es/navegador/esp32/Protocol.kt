package es.navegador.esp32

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.CRC32

/**
 * Paquetes BLE que entiende la librería ChronosESP32 (la misma que usa el firmware).
 *
 * Formato de un paquete:  AB  len_hi len_lo  FE|FF  comando  datos…
 * donde len = longitud total − 3.
 *
 * Si un paquete no cabe en una sola escritura BLE se trocea así: el primer trozo lleva
 * los 20 primeros bytes y cada trozo siguiente lleva un índice (0, 1, 2…) seguido de
 * 19 bytes. Ver ChronosESP32::onWrite.
 */
object Protocol {
    /** El búfer de la librería es de 512 bytes; dejamos margen. */
    const val MAX_PACKET = 500
    const val ICON_BYTES = 288 // 48 x 48 / 8
    private const val ICON_CHUNK = 96

    private const val CMD_TIME = 0x93
    private const val CMD_NAV = 0xEF
    private const val CMD_NAV_ICON = 0xEE

    private const val LIMIT_SHORT = 40
    private const val LIMIT_INSTRUCTION = 300

    /** Fecha y hora (comando 0x93). El mes va de 1 a 12. */
    fun timePacket(t: LocalDateTime): ByteArray = bytes(
        0xAB, 0x00, 0x0B, 0xFF, CMD_TIME, 0x70, 0x00,
        (t.year shr 8) and 0xFF, t.year and 0xFF,
        t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second,
    )

    /** Navegación terminada (comando 0xEF, estado 0x00). */
    fun navInactivePacket(): ByteArray = packet(CMD_NAV, bytes(0x00))

    /**
     * Navegación activa (comando 0xEF, estado 0x80). Los textos van terminados en 0, en este orden:
     * título (distancia a la maniobra), duración, distancia, llegada, indicación, velocidad.
     */
    fun navPacket(nav: NavInfo, iconCrc: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(if (nav.icon != null) 1 else 0)
        out.write(1) // es navegación (no información general)
        out.write(((iconCrc shr 24) and 0xFF).toInt())
        out.write(((iconCrc shr 16) and 0xFF).toInt())
        out.write(((iconCrc shr 8) and 0xFF).toInt())
        out.write((iconCrc and 0xFF).toInt())
        listOf(
            truncateUtf8(nav.maneuverDistance, LIMIT_SHORT),
            truncateUtf8(nav.duration, LIMIT_SHORT),
            truncateUtf8(nav.distance, LIMIT_SHORT),
            truncateUtf8(nav.eta, LIMIT_SHORT),
            truncateUtf8(nav.instruction, LIMIT_INSTRUCTION),
            "", // velocidad: no se usa
        ).forEach {
            out.write(it.toByteArray(Charsets.UTF_8))
            out.write(0)
        }
        return packet(CMD_NAV, out.toByteArray())
    }

    /** Icono de la maniobra en 3 paquetes de 96 bytes (comando 0xEE). */
    fun iconPackets(icon: ByteArray, crc: Long): List<ByteArray> {
        require(icon.size == ICON_BYTES) { "El icono debe tener $ICON_BYTES bytes" }
        return (0 until ICON_BYTES / ICON_CHUNK).map { pos ->
            val body = ByteArray(6 + ICON_CHUNK)
            body[0] = 0
            body[1] = pos.toByte()
            body[2] = ((crc shr 24) and 0xFF).toByte()
            body[3] = ((crc shr 16) and 0xFF).toByte()
            body[4] = ((crc shr 8) and 0xFF).toByte()
            body[5] = (crc and 0xFF).toByte()
            icon.copyInto(body, 6, pos * ICON_CHUNK, (pos + 1) * ICON_CHUNK)
            packet(CMD_NAV_ICON, body)
        }
    }

    fun iconCrc(icon: ByteArray): Long = CRC32().apply { update(icon) }.value

    /** Trocea un paquete para escrituras de como mucho [maxWrite] bytes. */
    fun split(packet: ByteArray, maxWrite: Int): List<ByteArray> {
        if (packet.size <= maxWrite) return listOf(packet)
        val parts = mutableListOf(packet.copyOfRange(0, 20))
        var pos = 20
        var index = 0
        while (pos < packet.size) {
            val end = minOf(pos + 19, packet.size)
            parts += byteArrayOf(index.toByte()) + packet.copyOfRange(pos, end)
            pos = end
            index++
        }
        return parts
    }

    /** Recorta a [maxBytes] bytes en UTF-8 sin partir nunca un carácter. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        val clean = text.replace('\u0000', ' ')
        if (clean.toByteArray(Charsets.UTF_8).size <= maxBytes) return clean
        val ellipsis = "…"
        val budget = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val size = chunk.toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            sb.append(chunk)
            used += size
            i += Character.charCount(cp)
        }
        return sb.toString().trimEnd() + ellipsis
    }

    private fun packet(cmd: Int, body: ByteArray): ByteArray {
        val total = 5 + body.size
        require(total <= MAX_PACKET) { "Paquete demasiado grande: $total bytes" }
        val len = total - 3
        return bytes(0xAB, (len shr 8) and 0xFF, len and 0xFF, 0xFE, cmd) + body
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
