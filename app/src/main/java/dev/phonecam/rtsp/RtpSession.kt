package dev.phonecam.rtsp

import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import kotlin.random.Random

/** One playing client: turns frames into RTP packets on its own sequence numbers and SSRC. */
class RtpSession(
    val id: String,
    private val transport: RtpTransport,
    maxPacketSize: Int,
    private val requestKeyFrame: () -> Unit,
) {
    val ssrc = Random.nextInt()
    private val packetizer = RtpH264Packetizer(ssrc, transport, maxPacketSize)
    private val timestampOffset = Random.nextInt()
    private var waitingForKeyFrame = true

    private var statsStart = SystemClock.elapsedRealtime()
    private var statsFrames = 0
    private var statsDropped = 0
    private var statsPackets = packetizer.packetsSent

    /** Called on the encoder thread for every frame while the session is playing. */
    fun sendFrame(sps: ByteArray, pps: ByteArray, nals: List<ByteBuffer>, ptsUs: Long, isKeyFrame: Boolean) {
        if (waitingForKeyFrame && !isKeyFrame) {
            // Without a key frame first the decoder would only show grey/smeared garbage.
            statsDropped++
            logStats()
            return
        }
        waitingForKeyFrame = false

        val timestamp = timestampOffset + (ptsUs * 9 / 100).toInt() // 90 kHz RTP clock
        transport.beginFrame()
        if (isKeyFrame) {
            // Repeat SPS/PPS before every key frame so a client that joined late, or lost
            // packets, can always start decoding again from here.
            packetizer.send(sps, timestamp, false)
            packetizer.send(pps, timestamp, false)
        }
        for (i in nals.indices) packetizer.send(nals[i], timestamp, i == nals.lastIndex)
        if (transport.endFrame()) {
            statsFrames++
        } else {
            // The network fell behind (TCP queue full): skip ahead to the next key frame
            // instead of queueing old frames, and ask for that key frame now.
            statsDropped++
            waitingForKeyFrame = true
            requestKeyFrame()
        }
        logStats()
    }

    private fun logStats() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsStart
        if (elapsed < STATS_INTERVAL_MS) return
        val packets = packetizer.packetsSent - statsPackets
        Log.i(TAG, "[$id] ${transport.description}: sent %.1f fps, dropped %d, %d packets/s".format(
            statsFrames * 1000f / elapsed, statsDropped, packets * 1000 / elapsed))
        statsStart = now
        statsFrames = 0
        statsDropped = 0
        statsPackets = packetizer.packetsSent
    }

    fun close() = transport.close()

    /** For the RTP-Info header in the PLAY response. */
    val nextSequence get() = packetizer.sequence

    private companion object {
        const val TAG = "PhoneCam/Rtp"
        const val STATS_INTERVAL_MS = 5000L
    }
}
