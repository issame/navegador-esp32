package es.navegador.esp32

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconBitsTest {
    private val s = IconBits.SIZE

    @Test
    fun transparentBackground_usesAlpha_msbIsLeftPixel() {
        val px = IntArray(s * s) // todo transparente
        px[0] = 0xFF2266FF.toInt()        // (0,0) opaco
        px[s + 9] = 0xFFFFFFFF.toInt()    // (9,1) opaco
        val bits = IconBits.pack(px)!!
        assertEquals(0x80, bits[0].toInt() and 0xFF)
        // fila 1 empieza en el byte 6; x=9 -> byte 7, bit 6
        assertEquals(0x40, bits[7].toInt() and 0xFF)
    }

    @Test
    fun opaqueImage_comparesWithCornerColor() {
        val px = IntArray(s * s) { 0xFF1A73E8.toInt() } // fondo azul opaco
        px[2 * s + 2] = 0xFFFFFFFF.toInt()
        val bits = IconBits.pack(px)!!
        val i = 2 * s + 2
        assertEquals(0x80 ushr (i % 8), bits[i / 8].toInt() and 0xFF)
        assertEquals(1, bits.sumOf { Integer.bitCount(it.toInt() and 0xFF) })
    }

    @Test
    fun emptyIcon_isNull() {
        assertNull(IconBits.pack(IntArray(s * s)))
    }

    @Test
    fun unpack_matchesFirmwareBitOrder() {
        val bits = ByteArray(288)
        bits[0] = 0x01 // x = 7 en la fila 0
        val px = IconBits.unpack(bits)
        assertEquals(0xFFFFFFFF.toInt(), px[7])
        assertEquals(0xFF000000.toInt(), px[0])
    }
}
