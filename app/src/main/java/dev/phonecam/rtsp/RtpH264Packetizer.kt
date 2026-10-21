package dev.phonecam.rtsp

import java.nio.ByteBuffer

/**
 * Packs H.264 NAL units into RTP packets (RFC 6184): a NAL unit that fits goes in one packet
 * (single NAL unit mode), a bigger one is split into FU-A fragments. Each packet is handed to
 * [transport] straight away from one reusable buffer.
 */
class RtpH264Packetizer(
    private val ssrc: Int,
    private val transport: RtpTransport,
    /** Whole RTP packet size limit; smaller means more packets but less lost per bad packet. */
    maxPacketSize: Int = DEFAULT_MAX_PACKET_SIZE,
) {
    private val packet = ByteArray(maxPacketSize)
    private val maxPayload = maxPacketSize - HEADER_SIZE
    var sequence = (Math.random() * 0xFFFF).toInt()
        private set
    var packetsSent = 0L
        private set

    /** [nal] has no start code. [lastInAccessUnit] sets the RTP marker bit (end of the frame). */
    fun send(nal: ByteBuffer, timestamp: Int, lastInAccessUnit: Boolean) {
        val src = nal.duplicate()
        val size = src.remaining()
        if (size <= maxPayload) {
            writeHeader(timestamp, lastInAccessUnit)
            src.get(packet, HEADER_SIZE, size)
            emit(HEADER_SIZE + size)
            return
        }

        // FU-A: drop the NAL header byte and carry its fields in the FU indicator and FU header.
        val nalHeader = src.get().toInt() and 0xFF
        val fuIndicator = (nalHeader and 0xE0) or FU_A
        val type = nalHeader and 0x1F
        var first = true
        while (src.hasRemaining()) {
            val chunk = minOf(src.remaining(), maxPayload - 2)
            val last = chunk == src.remaining()
            writeHeader(timestamp, lastInAccessUnit && last)
            packet[HEADER_SIZE] = fuIndicator.toByte()
            packet[HEADER_SIZE + 1] = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()
            src.get(packet, HEADER_SIZE + 2, chunk)
            emit(HEADER_SIZE + 2 + chunk)
            first = false
        }
    }

    fun send(nal: ByteArray, timestamp: Int, lastInAccessUnit: Boolean) =
        send(ByteBuffer.wrap(nal), timestamp, lastInAccessUnit)

    private fun writeHeader(timestamp: Int, marker: Boolean) {
        packet[0] = 0x80.toByte() // version 2, no padding, no extension, no CSRCs
        packet[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        packet[2] = (sequence shr 8).toByte()
        packet[3] = sequence.toByte()
        packet[4] = (timestamp shr 24).toByte()
        packet[5] = (timestamp shr 16).toByte()
        packet[6] = (timestamp shr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = ssrc.toByte()
        sequence = (sequence + 1) and 0xFFFF
    }

    private fun emit(length: Int) {
        transport.send(packet, length)
        packetsSent++
    }

    companion object {
        const val PAYLOAD_TYPE = 96
        const val HEADER_SIZE = 12
        /** Kept under the typical Wi-Fi/Ethernet MTU so IP never fragments a packet. */
        const val DEFAULT_MAX_PACKET_SIZE = 1400
        private const val FU_A = 28
    }
}
