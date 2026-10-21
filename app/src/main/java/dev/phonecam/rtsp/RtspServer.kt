package dev.phonecam.rtsp

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/** What the server needs from the video side. */
interface H264Source {
    /** SPS and PPS without start codes, or null while the encoder is still starting. */
    val parameterSets: Pair<ByteArray, ByteArray>?
    val frameRate: Int
    fun requestKeyFrame()

    /** A client is about to play (DESCRIBE/PLAY): start capturing if not already running. */
    fun onClientInterested()
}

/**
 * Minimal RTSP server (RFC 2326) for one H.264 track at rtsp://<ip>:<port>/cam. RTP goes over
 * UDP unless the client asks for TCP, in which case it is interleaved in the RTSP connection.
 * One thread per RTSP connection; RTP is sent from the caller of [onFrame] (the encoder thread).
 */
class RtspServer(
    private val port: Int,
    private val maxPacketSize: Int,
    private val source: H264Source,
    private val onClientsChanged: (Int) -> Unit,
) {
    private val playing = CopyOnWriteArrayList<RtpSession>()
    private val connections = CopyOnWriteArrayList<Connection>()
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var running = false

    fun start() {
        // DSCP AF41 (video): Wi-Fi WMM maps it to the video access category.
        udpSocket = DatagramSocket().apply {
            trafficClass = TRAFFIC_CLASS_VIDEO
            sendBufferSize = 256 * 1024
        }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = server
        running = true
        thread(name = "rtsp-accept") {
            Log.i(TAG, "RTSP server listening on port $port, RTP/UDP from port ${udpSocket?.localPort}, " +
                "max packet $maxPacketSize B")
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (running) Log.e(TAG, "accept failed", e)
                    break
                }
                val connection = Connection(socket)
                connections.add(connection)
                thread(name = "rtsp-${socket.inetAddress.hostAddress}") { connection.run() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        connections.forEach { it.close() }
        connections.clear()
        playing.clear()
        runCatching { udpSocket?.close() }
        Log.i(TAG, "RTSP server stopped")
    }

    /** Called on the encoder thread for every encoded frame. */
    fun onFrame(nals: List<ByteBuffer>, ptsUs: Long, isKeyFrame: Boolean) {
        if (playing.isEmpty()) return
        val (sps, pps) = source.parameterSets ?: return
        for (session in playing) session.sendFrame(sps, pps, nals, ptsUs, isKeyFrame)
    }

    private class Request(val method: String, val uri: String, val headers: Map<String, String>) {
        val cseq get() = headers["cseq"] ?: "0"
        override fun toString() = "$method $uri"
    }

    private inner class Connection(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val writeLock = Any()
        private val sessionId = "%08X".format(Random.nextInt())
        private val client = "${socket.inetAddress.hostAddress}:${socket.port}"
        private var transport: RtpTransport? = null
        private var session: RtpSession? = null
        @Volatile private var closed = false

        fun run() {
            Log.i(TAG, "[$client] connected")
            try {
                socket.tcpNoDelay = true
                socket.trafficClass = TRAFFIC_CLASS_VIDEO
                // Clients send keep-alives (or RTCP over TCP) well within the advertised 60 s
                // session timeout; silence beyond that means the client is gone.
                socket.soTimeout = READ_TIMEOUT_MS
                while (!closed) {
                    val request = readRequest() ?: break
                    Log.i(TAG, "[$client] -> $request ${request.headers["transport"] ?: ""}")
                    if (!handle(request)) break
                }
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "[$client] connection error: ${e.message}")
            } finally {
                close()
            }
        }

        /** Returns false when the connection should end. */
        private fun handle(req: Request): Boolean {
            when (req.method) {
                "OPTIONS" -> respond(req, "200 OK", "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER")
                "DESCRIBE" -> describe(req)
                "SETUP" -> setup(req)
                "PLAY" -> play(req)
                "PAUSE" -> {
                    session?.let { if (playing.remove(it)) onClientsChanged(playing.size) }
                    respond(req, "200 OK", "Session" to sessionId)
                }
                "GET_PARAMETER", "SET_PARAMETER" -> respond(req, "200 OK", "Session" to sessionId) // keep-alive
                "TEARDOWN" -> {
                    respond(req, "200 OK", "Session" to sessionId)
                    return false
                }
                else -> respond(req, "501 Not Implemented")
            }
            return true
        }

        private fun describe(req: Request) {
            if (!pathOf(req.uri).trimEnd('/').endsWith(PATH)) {
                respond(req, "404 Not Found")
                return
            }
            source.onClientInterested()
            // A client can connect while the encoder is still starting; wait for SPS/PPS.
            var params = source.parameterSets
            var waited = 0
            while (params == null && waited < 5000) {
                Thread.sleep(50)
                waited += 50
                params = source.parameterSets
            }
            if (params == null) {
                respond(req, "503 Service Unavailable")
                return
            }
            val (sps, pps) = params
            val host = socket.localAddress.hostAddress
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- ${System.currentTimeMillis()} 1 IN IP4 $host\r\n")
                append("s=PhoneCam\r\n")
                append("c=IN IP4 0.0.0.0\r\n")
                append("t=0 0\r\n")
                append("a=control:*\r\n")
                append("a=range:npt=0-\r\n")
                append("m=video 0 RTP/AVP ${RtpH264Packetizer.PAYLOAD_TYPE}\r\n")
                append("a=rtpmap:${RtpH264Packetizer.PAYLOAD_TYPE} H264/90000\r\n")
                append("a=fmtp:${RtpH264Packetizer.PAYLOAD_TYPE} packetization-mode=1")
                append(";profile-level-id=%02x%02x%02x".format(
                    sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF))
                append(";sprop-parameter-sets=${b64(sps)},${b64(pps)}\r\n")
                append("a=framerate:${source.frameRate}\r\n")
                append("a=control:$TRACK\r\n")
            }
            respond(req, "200 OK",
                "Content-Base" to "rtsp://$host:$port$PATH/",
                "Content-Type" to "application/sdp",
                body = sdp)
        }

        private fun setup(req: Request) {
            if (transport != null) {
                respond(req, "459 Aggregate Operation Not Allowed")
                return
            }
            // A client may list several transports separated by commas; take the first.
            val spec = (req.headers["transport"] ?: "").substringBefore(',')
            val params = spec.split(';').associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "") }
            val reply: String
            if (spec.uppercase().startsWith("RTP/AVP/TCP")) {
                val channels = params["interleaved"]?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                val rtpChannel = channels.getOrElse(0) { 0 }
                transport = TcpInterleavedRtpTransport(output, writeLock, rtpChannel) { close() }
                reply = "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-${channels.getOrElse(1) { rtpChannel + 1 }}"
            } else {
                val ports = params["client_port"]?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                val udp = udpSocket
                if (ports.isEmpty() || udp == null) {
                    respond(req, "461 Unsupported Transport")
                    return
                }
                transport = UdpRtpTransport(udp, socket.inetAddress, ports[0])
                val serverPort = udp.localPort
                reply = "RTP/AVP;unicast;client_port=${ports[0]}-${ports.getOrElse(1) { ports[0] + 1 }}" +
                    ";server_port=$serverPort-${serverPort + 1}"
            }
            Log.i(TAG, "[$client] transport: ${transport?.description}")
            respond(req, "200 OK",
                "Transport" to reply,
                "Session" to "$sessionId;timeout=60")
        }

        private fun play(req: Request) {
            val t = transport
            if (t == null) {
                respond(req, "455 Method Not Valid in This State")
                return
            }
            val s = session ?: RtpSession("$client/$sessionId", t, maxPacketSize, source::requestKeyFrame)
                .also { session = it }
            val base = "rtsp://${socket.localAddress.hostAddress}:$port$PATH/$TRACK"
            // Reply first, so in TCP mode the response is not mixed into the RTP that follows.
            respond(req, "200 OK",
                "Session" to sessionId,
                "Range" to "npt=0.000-",
                "RTP-Info" to "url=$base;seq=${s.nextSequence}")
            if (!playing.contains(s)) playing.add(s)
            source.onClientInterested()
            source.requestKeyFrame() // so the new client gets a picture right away
            onClientsChanged(playing.size)
            Log.i(TAG, "[$client] playing (${playing.size} client(s))")
        }

        private fun respond(req: Request, status: String, vararg headers: Pair<String, String>, body: String? = null) {
            val bodyBytes = body?.toByteArray()
            val text = buildString {
                append("RTSP/1.0 $status\r\n")
                append("CSeq: ${req.cseq}\r\n")
                append("Server: PhoneCam\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (bodyBytes != null) append("Content-Length: ${bodyBytes.size}\r\n")
                append("\r\n")
            }
            synchronized(writeLock) {
                output.write(text.toByteArray())
                if (bodyBytes != null) output.write(bodyBytes)
                output.flush()
            }
            Log.i(TAG, "[$client] <- $status")
        }

        /** Reads one request, skipping interleaved binary data the client sends (e.g. RTCP over TCP). */
        private fun readRequest(): Request? {
            while (true) {
                val first = input.read()
                if (first == -1) return null
                if (first == '$'.code) {
                    input.read() // channel
                    val hi = input.read()
                    val lo = input.read()
                    if (hi < 0 || lo < 0) return null
                    skip((hi shl 8) or lo)
                    continue
                }
                if (first == '\r'.code || first == '\n'.code) continue

                val requestLine = first.toChar() + (readLine() ?: return null)
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine() ?: return null
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                headers["content-length"]?.toIntOrNull()?.let { skip(it) }
                val parts = requestLine.split(' ')
                if (parts.size < 2) continue
                return Request(parts[0].uppercase(), parts[1], headers)
            }
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8192) throw IOException("line too long")
                sb.append(b.toChar())
            }
        }

        private fun skip(count: Int) {
            var left = count
            while (left > 0) {
                val n = input.skip(left.toLong())
                if (n <= 0) {
                    if (input.read() == -1) throw IOException("EOF in body")
                    left--
                } else {
                    left -= n.toInt()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            session?.let {
                if (playing.remove(it)) onClientsChanged(playing.size)
                it.close()
            } ?: transport?.close()
            runCatching { socket.close() }
            connections.remove(this)
            Log.i(TAG, "[$client] disconnected")
        }
    }

    private companion object {
        const val TAG = "PhoneCam/Rtsp"
        const val PATH = "/cam"
        const val TRACK = "track0"
        const val TRAFFIC_CLASS_VIDEO = 0x88 // DSCP 34 (AF41)
        const val READ_TIMEOUT_MS = 75_000

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun pathOf(uri: String): String =
            if (uri.startsWith("rtsp://", ignoreCase = true)) "/" + uri.substringAfter("://").substringAfter('/', "") else uri
    }
}
