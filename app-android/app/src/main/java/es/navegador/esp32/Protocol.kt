package es.navegador.esp32

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.CRC32

/**
 * Construye los paquetes BLE que entiende la librería ChronosESP32, que es la que usa
 * el firmware de la placa para recibir datos.
 *
 * Formato de cada paquete:
 * ```
 *   AB  longitud_alta longitud_baja  FE|FF  comando  datos…
 * ```
 * donde `longitud` = tamaño total del paquete − 3.
 *
 * Si el paquete no cabe en una sola escritura BLE se trocea: el primer trozo lleva los
 * 20 primeros bytes y cada trozo siguiente empieza por un índice (0, 1, 2…) seguido de
 * 19 bytes de datos. Así lo reconstruye `ChronosESP32::onWrite` en la placa.
 */
object Protocol {
    /** El búfer de recepción de la librería es de 512 bytes; se deja margen de seguridad. */
    const val MAX_PACKET_BYTES = 500

    /** La flecha de la maniobra mide 48 x 48 píxeles a 1 bit por píxel. */
    const val ICON_BYTES = 48 * 48 / 8

    /** La flecha se envía en 3 paquetes de 96 bytes. */
    private const val ICON_CHUNK_BYTES = 96

    private const val COMMAND_TIME = 0x93
    private const val COMMAND_NAVIGATION = 0xEF
    private const val COMMAND_NAVIGATION_ICON = 0xEE

    private const val NAVIGATION_ACTIVE = 0x80
    private const val NAVIGATION_INACTIVE = 0x00

    /**
     * Android no deja escribir más de 512 bytes de una vez en una característica BLE (límite
     * del estándar), aunque la MTU negociada permita más: lanza IllegalArgumentException.
     */
    const val MAX_BLE_WRITE_BYTES = 512

    /** Bytes que caben en una escritura con la MTU negociada, sin pasar del límite de Android. */
    fun maxWriteBytes(negotiatedMtu: Int): Int = minOf(negotiatedMtu - 3, MAX_BLE_WRITE_BYTES)

    /** Tamaño del primer trozo de un paquete troceado y de los datos de los siguientes. */
    private const val FIRST_CHUNK_BYTES = 20
    private const val NEXT_CHUNK_DATA_BYTES = 19

    /** Límites por texto para que el paquete de navegación nunca supere [MAX_PACKET_BYTES]. */
    private const val MAX_SHORT_TEXT_BYTES = 40
    private const val MAX_INSTRUCTION_BYTES = 300

    /** Paquete de fecha y hora (comando 0x93). El mes va de 1 a 12. */
    fun timePacket(time: LocalDateTime): ByteArray = bytesOf(
        0xAB, 0x00, 0x0B, 0xFF, COMMAND_TIME, 0x70, 0x00,
        (time.year shr 8) and 0xFF, time.year and 0xFF,
        time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second,
    )

    /** Paquete que avisa de que la navegación ha terminado (comando 0xEF, estado 0x00). */
    fun navigationEndedPacket(): ByteArray = packet(COMMAND_NAVIGATION, bytesOf(NAVIGATION_INACTIVE))

    /**
     * Paquete de navegación activa (comando 0xEF, estado 0x80).
     *
     * Después de la cabecera van 6 textos UTF-8 terminados en 0, en este orden:
     * distancia a la maniobra, duración, distancia restante, hora de llegada,
     * indicación y velocidad (esta última no se usa y va vacía).
     *
     * @param iconCrc identifica la flecha actual; la placa solo la redibuja si cambia.
     */
    fun navigationPacket(nav: NavInfo, iconCrc: Long): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(NAVIGATION_ACTIVE)
        body.write(if (nav.icon != null) 1 else 0) // ¿hay flecha?
        body.write(1) // 1 = navegación con maniobras (0 sería solo información)
        writeUInt32(body, iconCrc)
        listOf(
            truncateUtf8(nav.maneuverDistance, MAX_SHORT_TEXT_BYTES),
            truncateUtf8(nav.duration, MAX_SHORT_TEXT_BYTES),
            truncateUtf8(nav.distance, MAX_SHORT_TEXT_BYTES),
            truncateUtf8(nav.eta, MAX_SHORT_TEXT_BYTES),
            truncateUtf8(nav.instruction, MAX_INSTRUCTION_BYTES),
            "", // velocidad
        ).forEach { text ->
            body.write(text.toByteArray(Charsets.UTF_8))
            body.write(0)
        }
        return packet(COMMAND_NAVIGATION, body.toByteArray())
    }

    /**
     * Paquetes con la flecha de la maniobra (comando 0xEE): 3 trozos de 96 bytes.
     * Cada uno lleva su posición (0, 1, 2) y el CRC de la flecha completa.
     */
    fun iconPackets(icon: ByteArray, iconCrc: Long): List<ByteArray> {
        require(icon.size == ICON_BYTES) { "La flecha debe tener $ICON_BYTES bytes" }
        return (0 until ICON_BYTES / ICON_CHUNK_BYTES).map { position ->
            val body = ByteArrayOutputStream()
            body.write(0) // reservado; la librería no lo usa
            body.write(position)
            writeUInt32(body, iconCrc)
            body.write(icon, position * ICON_CHUNK_BYTES, ICON_CHUNK_BYTES)
            packet(COMMAND_NAVIGATION_ICON, body.toByteArray())
        }
    }

    /** CRC32 de la flecha, para saber si ha cambiado y hay que reenviarla. */
    fun iconCrc(icon: ByteArray): Long = CRC32().apply { update(icon) }.value

    /**
     * Divide un paquete en escrituras BLE de como mucho [maxWriteBytes] bytes
     * (MTU negociada − 3). Si cabe entero, se envía en una sola escritura.
     */
    fun split(packet: ByteArray, maxWriteBytes: Int): List<ByteArray> {
        if (packet.size <= maxWriteBytes) return listOf(packet)
        val chunks = mutableListOf(packet.copyOfRange(0, FIRST_CHUNK_BYTES))
        var offset = FIRST_CHUNK_BYTES
        var chunkIndex = 0
        while (offset < packet.size) {
            val end = minOf(offset + NEXT_CHUNK_DATA_BYTES, packet.size)
            chunks += byteArrayOf(chunkIndex.toByte()) + packet.copyOfRange(offset, end)
            offset = end
            chunkIndex++
        }
        return chunks
    }

    /**
     * Recorta [text] para que ocupe como mucho [maxBytes] bytes en UTF-8, sin partir
     * nunca un carácter (una "ñ" ocupa 2 bytes, un "€" 3). Si recorta, añade "…".
     */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        val withoutNulls = text.replace('\u0000', ' ') // el 0 separa textos en el paquete
        if (withoutNulls.toByteArray(Charsets.UTF_8).size <= maxBytes) return withoutNulls

        val ellipsis = "…"
        val budgetBytes = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size
        val result = StringBuilder()
        var usedBytes = 0
        var index = 0
        while (index < withoutNulls.length) {
            val codePoint = withoutNulls.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val characterBytes = character.toByteArray(Charsets.UTF_8).size
            if (usedBytes + characterBytes > budgetBytes) break
            result.append(character)
            usedBytes += characterBytes
            index += Character.charCount(codePoint)
        }
        return result.toString().trimEnd() + ellipsis
    }

    /** Añade la cabecera `AB longitud FE comando` a [body]. */
    private fun packet(command: Int, body: ByteArray): ByteArray {
        val totalBytes = 5 + body.size
        require(totalBytes <= MAX_PACKET_BYTES) { "Paquete demasiado grande: $totalBytes bytes" }
        val lengthField = totalBytes - 3
        return bytesOf(0xAB, (lengthField shr 8) and 0xFF, lengthField and 0xFF, 0xFE, command) + body
    }

    /** Escribe un entero de 32 bits sin signo, byte más alto primero. */
    private fun writeUInt32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
