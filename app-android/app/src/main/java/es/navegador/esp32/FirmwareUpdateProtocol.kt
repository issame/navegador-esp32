package es.navegador.esp32

import java.security.MessageDigest
import java.util.UUID

/**
 * Protocolo de actualización del firmware por Bluetooth (el lado de la placa está en
 * firmware/src/ota.cpp). Es un servicio BLE propio, separado del de ChronosESP32:
 *
 *  - Control (escribir / recibir avisos):
 *      `01 + tamaño (4 bytes, little endian) + MD5 (16 bytes)` → empezar
 *      `02` → terminar (la placa comprueba el MD5 y se reinicia)
 *      `03` → cancelar
 *    Los avisos traen 3 bytes: estado, porcentaje y código de error.
 *  - Datos (escribir): el archivo .bin, por orden, en trozos.
 *
 * No depende de Android para poder probarlo con tests normales.
 */
object FirmwareUpdateProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("c8659210-af91-4ad3-a995-a58d6fd26145")
    val CONTROL_UUID: UUID = UUID.fromString("c8659211-af91-4ad3-a995-a58d6fd26145")
    val DATA_UUID: UUID = UUID.fromString("c8659212-af91-4ad3-a995-a58d6fd26145")

    /** Tamaño de cada hueco de aplicación en la tabla de particiones del firmware. */
    const val MAX_FIRMWARE_BYTES = 0x1E0000

    /** Los binarios de ESP32 empiezan siempre por este byte. */
    private const val ESP_IMAGE_MAGIC = 0xE9.toByte()

    private const val COMMAND_START = 0x01
    const val COMMAND_FINISH = 0x02
    const val COMMAND_CANCEL = 0x03

    /** Estado que comunica la placa. */
    enum class BoardState { IDLE, RECEIVING, COMPLETED, FAILED }

    data class BoardStatus(val state: BoardState, val percent: Int, val errorCode: Int)

    /** Explica en castellano los códigos de error de la placa (ota.h). */
    fun errorDescription(code: Int): String = when (code) {
        1 -> "la placa no pudo empezar (¿firmware demasiado grande o sin tabla de particiones doble?)"
        2 -> "fallo al escribir en la memoria de la placa"
        3 -> "llegaron más o menos datos de los esperados"
        4 -> "el archivo llegó dañado o no es un firmware válido"
        5 -> "orden no reconocida"
        else -> "error desconocido ($code)"
    }

    /** Devuelve un motivo si [firmware] no parece un firmware válido para la placa, o null si vale. */
    fun validate(firmware: ByteArray): String? = when {
        firmware.isEmpty() -> "el archivo está vacío"
        firmware[0] != ESP_IMAGE_MAGIC -> "no es un archivo de firmware de ESP32 (.bin)"
        firmware.size > MAX_FIRMWARE_BYTES -> "es demasiado grande (${firmware.size / 1024} KB)"
        else -> null
    }

    fun md5(firmware: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(firmware)

    /** Orden de empezar: 01 + tamaño (little endian) + MD5. */
    fun startCommand(firmwareSize: Int, md5: ByteArray): ByteArray {
        require(md5.size == 16)
        return byteArrayOf(
            COMMAND_START.toByte(),
            (firmwareSize and 0xFF).toByte(),
            ((firmwareSize shr 8) and 0xFF).toByte(),
            ((firmwareSize shr 16) and 0xFF).toByte(),
            ((firmwareSize shr 24) and 0xFF).toByte(),
        ) + md5
    }

    /** Interpreta el aviso de estado de la placa (3 bytes), o null si no tiene ese formato. */
    fun parseStatus(value: ByteArray): BoardStatus? {
        if (value.size < 3) return null
        val state = BoardState.entries.getOrNull(value[0].toInt() and 0xFF) ?: return null
        return BoardStatus(state, value[1].toInt() and 0xFF, value[2].toInt() and 0xFF)
    }
}
