package dev.phonecam.rtsp

import dev.phonecam.encoder.forEachNal
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpH264PacketizerTest {
    private class Capture : RtpTransport {
        val packets = mutableListOf<ByteArray>()
        override fun send(packet: ByteArray, length: Int) { packets += packet.copyOf(length) }
        override fun close() {}
        override val description = "capture"
    }

    @Test
    fun smallNal_isOnePacketWithMarker() {
        val capture = Capture()
        val nal = byteArrayOf(0x65, 1, 2, 3)
        RtpH264Packetizer(0x11223344, capture).send(nal, 9000, true)

        assertEquals(1, capture.packets.size)
        val p = capture.packets[0]
        assertEquals(0x80, p[0].toInt() and 0xFF)
        assertEquals(0x80 or 96, p[1].toInt() and 0xFF) // marker + payload type
        assertArrayEquals(nal, p.copyOfRange(12, p.size))
    }

    @Test
    fun bigNal_isFuAFragmentsThatReassemble() {
        val capture = Capture()
        val nal = ByteArray(5000) { (it * 7).toByte() }.also { it[0] = 0x65 } // IDR, nri=3
        RtpH264Packetizer(1, capture).send(nal, 0, true)

        assertTrue(capture.packets.size > 1)
        assertTrue(capture.packets.all { it.size <= RtpH264Packetizer.DEFAULT_MAX_PACKET_SIZE })
        val rebuilt = ByteArrayOutputStream()
        capture.packets.forEachIndexed { i, p ->
            val indicator = p[12].toInt() and 0xFF
            val header = p[13].toInt() and 0xFF
            assertEquals(0x60 or 28, indicator) // nri from the NAL header, type FU-A
            assertEquals(i == 0, header and 0x80 != 0) // start bit
            assertEquals(i == capture.packets.lastIndex, header and 0x40 != 0) // end bit
            assertEquals(i == capture.packets.lastIndex, p[1].toInt() and 0x80 != 0) // marker
            if (i == 0) rebuilt.write((indicator and 0xE0) or (header and 0x1F))
            rebuilt.write(p, 14, p.size - 14)
        }
        assertArrayEquals(nal, rebuilt.toByteArray())

        val seqs = capture.packets.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        seqs.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 0xFFFF, b) }
    }

    @Test
    fun smallerPacketSize_isRespected() {
        val capture = Capture()
        val nal = ByteArray(5000) { it.toByte() }.also { it[0] = 0x41 }
        RtpH264Packetizer(1, capture, maxPacketSize = 800).send(nal, 0, true)

        assertTrue(capture.packets.all { it.size <= 800 })
        assertEquals(7, capture.packets.size) // 4999 payload bytes / (800 - 12 - 2) per fragment
    }

    @Test
    fun annexB_splitsOnThreeAndFourByteStartCodes() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6)
        val found = mutableListOf<List<Byte>>()
        val buf = ByteBuffer.wrap(data)
        forEachNal(buf, 0, data.size) { s, e -> found += data.copyOfRange(s, e).toList() }
        assertEquals(
            listOf(listOf<Byte>(0x67, 1, 2), listOf<Byte>(0x68, 3), listOf<Byte>(0x65, 4, 5, 6)),
            found,
        )
    }
}
