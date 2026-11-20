package es.navegador.esp32

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {

    private val accents = "Gira a la izquierda hacia la Plaça de l'Àngel · Peña ¿sí? ¡ñ! l·l Ŀ"

    private fun send(sim: ChronosLibrarySim, packet: ByteArray, mtu: Int) =
        Protocol.split(packet, mtu - 3).forEach(sim::onWrite)

    @Test
    fun timePacket_bytesAndDecode() {
        val p = Protocol.timePacket(LocalDateTime.of(2026, 11, 19, 14, 32, 5))
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0, 0x0B, 0xFF.toByte(), 0x93.toByte(), 0x70, 0, 0x07, 0xEA.toByte(), 11, 19, 14, 32, 5),
            p,
        )
        val sim = ChronosLibrarySim()
        send(sim, p, 23)
        assertArrayEquals(intArrayOf(5, 32, 14, 19, 11, 2026), sim.time)
    }

    @Test
    fun navPacket_roundTrip_smallMtu_withAccents() {
        val nav = NavInfo(true, "250 m", accents, "19 min", "8,4 km", "14:51", ByteArray(288) { 1 })
        val crc = Protocol.iconCrc(nav.icon!!)
        val packet = Protocol.navigationPacket(nav, crc)
        assertTrue("debe necesitar trocearse", packet.size > 20)

        val sim = ChronosLibrarySim()
        send(sim, packet, 23) // MTU por defecto: 20 bytes por escritura
        assertEquals(1, sim.packets)
        assertTrue(sim.navActive)
        assertTrue(sim.navHasIcon)
        assertTrue(sim.navIsNavigation)
        assertEquals(crc, sim.navCrc)
        assertEquals(listOf("250 m", "19 min", "8,4 km", "14:51", accents, ""), sim.navStrings)
    }

    @Test
    fun navPacket_roundTrip_bigMtu_singleWrite() {
        val nav = NavInfo(true, "1,2 km", accents, "1 h 5 min", "120 km", "16:05")
        val packet = Protocol.navigationPacket(nav, 0xFFFFFFFFL)
        assertEquals(1, Protocol.split(packet, 514).size)
        val sim = ChronosLibrarySim()
        send(sim, packet, 517)
        assertFalse(sim.navHasIcon)
        assertEquals(accents, sim.navStrings[4])
    }

    @Test
    fun navInactive_decodes() {
        val sim = ChronosLibrarySim()
        send(sim, Protocol.navigationPacket(NavInfo(true, "1 m", "x"), 0), 23)
        assertTrue(sim.navActive)
        send(sim, Protocol.navigationEndedPacket(), 23)
        assertFalse(sim.navActive)
    }

    @Test
    fun iconPackets_roundTrip() {
        val icon = ByteArray(288) { (it * 7).toByte() }
        val crc = Protocol.iconCrc(icon)
        val sim = ChronosLibrarySim()
        Protocol.iconPackets(icon, crc).forEach { send(sim, it, 23) }
        assertEquals(listOf(0, 1, 2), sim.iconParts)
        assertEquals(crc, sim.navCrc)
        assertArrayEquals(icon, sim.icon)
    }

    @Test
    fun longInstruction_isTruncatedWithoutBreakingUtf8_andFitsBuffer() {
        val long = "ñàçé".repeat(200)
        val packet = Protocol.navigationPacket(NavInfo(true, "250 m", long), 0)
        assertTrue(packet.size <= Protocol.MAX_PACKET_BYTES)
        val sim = ChronosLibrarySim()
        send(sim, packet, 23)
        val got = sim.navStrings[4]
        assertTrue(got.endsWith("…"))
        assertFalse("sin caracteres rotos", got.contains('�'))
        assertTrue(got.toByteArray(Charsets.UTF_8).size <= 300)
    }

    @Test
    fun truncateUtf8_keepsShortText() {
        assertEquals("Peña", Protocol.truncateUtf8("Peña", 40))
        assertEquals("ab…", Protocol.truncateUtf8("abcdefgh", 5))
    }

    @Test
    fun maxWriteBytes_neverExceedsAndroidLimit() {
        // Con la MTU máxima (517) quedarían 514 bytes, pero Android solo admite 512.
        assertEquals(512, Protocol.maxWriteBytes(517))
        assertEquals(244, Protocol.maxWriteBytes(247))
        assertEquals(20, Protocol.maxWriteBytes(23))
    }

    @Test
    fun phoneBatteryPacket_decodes() {
        val sim = ChronosLibrarySim()
        send(sim, Protocol.phoneBatteryPacket(85, charging = true), 23)
        assertEquals(85, sim.phoneBattery)
        assertTrue(sim.phoneCharging)
        send(sim, Protocol.phoneBatteryPacket(14, charging = false), 23)
        assertEquals(14, sim.phoneBattery)
        assertFalse(sim.phoneCharging)
    }

    @Test
    fun arrivalPacket_isInformationWithMarkerTitle() {
        val sim = ChronosLibrarySim()
        send(sim, Protocol.navigationPacket(NavInfo.ARRIVED, 0xFFFFFFFFL), 23)
        assertTrue(sim.navActive)
        assertFalse(sim.navIsNavigation)
        assertFalse(sim.navHasIcon)
        assertEquals(Protocol.ARRIVAL_MARKER, sim.navStrings[0])
    }
}
