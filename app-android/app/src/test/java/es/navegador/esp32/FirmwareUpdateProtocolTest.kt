package es.navegador.esp32

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareUpdateProtocolTest {

    @Test
    fun startCommand_sizeIsLittleEndian_followedByMd5() {
        val md5 = ByteArray(16) { it.toByte() }
        val command = FirmwareUpdateProtocol.startCommand(0x0F1234, md5)
        assertEquals(1 + 4 + 16, command.size)
        assertArrayEquals(byteArrayOf(0x01, 0x34, 0x12, 0x0F, 0x00), command.copyOfRange(0, 5))
        assertArrayEquals(md5, command.copyOfRange(5, 21))
    }

    @Test
    fun validate_acceptsEspImage_rejectsOthers() {
        assertNull(FirmwareUpdateProtocol.validate(byteArrayOf(0xE9.toByte(), 1, 2, 3)))
        assertNotNull(FirmwareUpdateProtocol.validate(ByteArray(0)))
        assertNotNull(FirmwareUpdateProtocol.validate("PK zip".toByteArray()))
        val tooBig = ByteArray(FirmwareUpdateProtocol.MAX_FIRMWARE_BYTES + 1).also { it[0] = 0xE9.toByte() }
        assertNotNull(FirmwareUpdateProtocol.validate(tooBig))
    }

    @Test
    fun md5_matchesKnownValue() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        val hex = FirmwareUpdateProtocol.md5("abc".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex)
    }

    @Test
    fun parseStatus_readsStatePercentAndError() {
        val status = FirmwareUpdateProtocol.parseStatus(byteArrayOf(3, 42, 4))!!
        assertEquals(FirmwareUpdateProtocol.BoardState.FAILED, status.state)
        assertEquals(42, status.percent)
        assertEquals(4, status.errorCode)
        assertNull(FirmwareUpdateProtocol.parseStatus(byteArrayOf(1)))
        assertNull(FirmwareUpdateProtocol.parseStatus(byteArrayOf(9, 0, 0)))
    }
}
