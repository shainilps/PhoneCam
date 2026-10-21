package dev.phonecam.rtsp

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Where RTP packets go. A frame is sent as beginFrame(), send() for each packet, endFrame().
 * endFrame() returns false if the frame had to be dropped because the network is behind.
 */
interface RtpTransport {
    fun beginFrame() {}
    fun send(packet: ByteArray, length: Int)
    fun endFrame(): Boolean = true
    fun close()
    val description: String
}

/** RTP over UDP: each packet goes out at once; a lost packet is just gone, nothing waits for it. */
class UdpRtpTransport(
    private val socket: DatagramSocket,
    address: InetAddress,
    private val port: Int,
) : RtpTransport {
    private val datagram = DatagramPacket(ByteArray(0), 0, address, port)
    var sendErrors = 0
        private set

    override fun send(packet: ByteArray, length: Int) {
        datagram.setData(packet, 0, length)
        try {
            socket.send(datagram)
        } catch (e: IOException) {
            if (sendErrors++ % 100 == 0) Log.w(TAG, "UDP send to ${datagram.address}:$port failed ($sendErrors)", e)
        }
    }

    override fun close() {} // the socket is shared and owned by the server

    override val description = "UDP ${address.hostAddress}:$port"

    private companion object {
        const val TAG = "PhoneCam/Rtp"
    }
}

/**
 * RTP interleaved in the RTSP TCP connection ($ + channel + length + packet). A writer thread
 * drains a short queue of whole frames; when the queue is full the frame is dropped instead of
 * letting delay build up.
 */
class TcpInterleavedRtpTransport(
    private val out: OutputStream,
    private val writeLock: Any,
    private val channel: Int,
    private val onWriteError: () -> Unit,
) : RtpTransport {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_FRAMES)
    private var frame = ByteArray(256 * 1024)
    private var frameLength = 0
    @Volatile private var closed = false

    private val writer = Thread({
        try {
            while (!closed) {
                val data = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                synchronized(writeLock) {
                    out.write(data)
                    out.flush()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            if (!closed) {
                Log.w(TAG, "TCP write failed", e)
                onWriteError()
            }
        }
    }, "rtp-tcp-writer").apply { start() }

    override fun beginFrame() {
        frameLength = 0
    }

    override fun send(packet: ByteArray, length: Int) {
        val needed = frameLength + 4 + length
        if (needed > frame.size) frame = frame.copyOf(maxOf(needed, frame.size * 2))
        frame[frameLength] = '$'.code.toByte()
        frame[frameLength + 1] = channel.toByte()
        frame[frameLength + 2] = (length shr 8).toByte()
        frame[frameLength + 3] = length.toByte()
        System.arraycopy(packet, 0, frame, frameLength + 4, length)
        frameLength = needed
    }

    override fun endFrame(): Boolean = !closed && queue.offer(frame.copyOf(frameLength))

    override fun close() {
        closed = true
        writer.interrupt()
    }

    override val description = "TCP interleaved channel $channel"

    private companion object {
        const val TAG = "PhoneCam/Rtp"
        const val MAX_QUEUED_FRAMES = 3
    }
}
