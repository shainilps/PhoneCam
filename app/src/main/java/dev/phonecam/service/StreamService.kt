package dev.phonecam.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.phonecam.MainActivity
import dev.phonecam.encoder.CameraEncoder
import dev.phonecam.encoder.EncodedVideoSink
import dev.phonecam.encoder.VideoConfig
import dev.phonecam.rtsp.H264Source
import dev.phonecam.rtsp.RtpH264Packetizer
import dev.phonecam.rtsp.RtspServer
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * Foreground service that owns the camera/encoder and the RTSP server and wires one to the
 * other. Running as a foreground service keeps the camera working with the screen off.
 */
class StreamService : Service(), EncodedVideoSink, H264Source {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var encoder: CameraEncoder? = null
    @Volatile private var server: RtspServer? = null
    private var config = VideoConfig()
    @Volatile private var clients = 0
    private val idleCheck = Runnable { if (clients == 0) stopCamera() }
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var urls: List<StreamUrl> = emptyList()

    @Volatile override var parameterSets: Pair<ByteArray, ByteArray>? = null
        private set
    override var frameRate = 30
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested")
            stopSelf()
        } else if (server == null) {
            val packetSize = intent?.getIntExtra(EXTRA_PACKET_SIZE, DEFAULT_PACKET_SIZE) ?: DEFAULT_PACKET_SIZE
            startStreaming(intent?.toConfig() ?: VideoConfig(), packetSize)
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(config: VideoConfig, maxPacketSize: Int) {
        Log.i(TAG, "Starting: $config, max packet $maxPacketSize B")
        this.config = config
        frameRate = config.fps
        urls = findUrls()
        startForegroundWithNotification(clients = 0)
        acquireWakeLock()

        val rtsp = RtspServer(PORT, maxPacketSize, this) { count ->
            clients = count
            StreamState.update { it.copy(clients = count) }
            mainHandler.post {
                updateNotification(count)
                if (count == 0) scheduleIdleCheck()
            }
        }
        try {
            rtsp.start()
        } catch (e: IOException) {
            Log.e(TAG, "Can't open port $PORT", e)
            StreamState.update { it.copy(error = "Can't open port $PORT: ${e.message}") }
            stopSelf()
            return
        }
        server = rtsp
        StreamState.update { StreamStatus(running = true, config = config, maxPacketSize = maxPacketSize, urls = urls) }
        urls.forEach { Log.i(TAG, "Stream URL: ${it.url} (${it.network})") }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleCheck)
        stopCamera()
        server?.stop()
        server = null
        releaseLocks()
        StreamState.update { StreamStatus(error = it.error) }
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    // ---- EncodedVideoSink (encoder thread) ----

    override fun onParameterSets(sps: ByteArray, pps: ByteArray) {
        parameterSets = sps to pps
    }

    override fun onFrame(nals: List<ByteBuffer>, ptsUs: Long, isKeyFrame: Boolean) {
        server?.onFrame(nals, ptsUs, isKeyFrame)
    }

    override fun onError(message: String) {
        StreamState.update { it.copy(error = message) }
        mainHandler.post { stopSelf() }
    }

    // ---- H264Source (RTSP threads) ----

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun onClientInterested() {
        mainHandler.post { startCamera() }
    }

    // ---- Camera on demand (main thread) ----

    /** Starts capture when a client wants video; the camera stays off while nobody watches. */
    private fun startCamera() {
        if (server == null) return
        if (encoder == null) {
            Log.i(TAG, "Client wants video: starting camera")
            acquireWifiLock()
            encoder = CameraEncoder(this, config, this).also { it.start() }
            StreamState.update { it.copy(cameraOn = true) }
        }
        if (clients == 0) scheduleIdleCheck() // e.g. a client that described but never played
    }

    private fun stopCamera() {
        val e = encoder ?: return
        Log.i(TAG, "No clients: stopping camera")
        e.stop()
        encoder = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        StreamState.update { it.copy(cameraOn = false) }
    }

    private fun scheduleIdleCheck() {
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
    }

    // ---- Locks ----

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // Keeps the Wi-Fi radio out of power save, which otherwise delays packets by up to
        // ~100 ms (seen on the Redmi 6 as a ping sawtooth).
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "PhoneCam:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Keeps the CPU awake so the server keeps answering with the screen off. */
    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam:stream").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    // ---- Notification ----

    private fun startForegroundWithNotification(clients: Int) {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Streaming")
                .build()
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(clients), type)
    }

    private fun updateNotification(clients: Int) {
        if (server == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(clients))
    }

    private fun buildNotification(clients: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PhoneCam streaming")
            .setContentText("${urls.firstOrNull()?.url ?: "no network"} · $clients client(s)")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun findUrls(): List<StreamUrl> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { StreamUrl("rtsp://${it.hostAddress}:$PORT/cam", nif.name) }
            }
    } catch (e: IOException) {
        Log.w(TAG, "Can't list network interfaces", e)
        emptyList()
    }

    companion object {
        private const val TAG = "PhoneCam/Service"
        const val PORT = 8554
        private const val IDLE_STOP_MS = 10_000L
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.phonecam.STOP"
        private const val EXTRA_PACKET_SIZE = "maxPacketSize"
        const val DEFAULT_PACKET_SIZE = RtpH264Packetizer.DEFAULT_MAX_PACKET_SIZE

        fun start(context: Context, config: VideoConfig, maxPacketSize: Int) {
            val intent = Intent(context, StreamService::class.java)
                .putExtra("width", config.width)
                .putExtra("height", config.height)
                .putExtra("fps", config.fps)
                .putExtra("bitrateKbps", config.bitrateKbps)
                .putExtra("keyFrameIntervalSec", config.keyFrameIntervalSec)
                .putExtra("frontCamera", config.frontCamera)
                .putExtra("exposureCompensation", config.exposureCompensation)
                .putExtra(EXTRA_PACKET_SIZE, maxPacketSize)
            StreamState.update { it.copy(error = null) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.toConfig(): VideoConfig {
            val d = VideoConfig()
            return VideoConfig(
                width = getIntExtra("width", d.width),
                height = getIntExtra("height", d.height),
                fps = getIntExtra("fps", d.fps),
                bitrateKbps = getIntExtra("bitrateKbps", d.bitrateKbps),
                keyFrameIntervalSec = getFloatExtra("keyFrameIntervalSec", d.keyFrameIntervalSec),
                frontCamera = getBooleanExtra("frontCamera", d.frontCamera),
                exposureCompensation = getIntExtra("exposureCompensation", d.exposureCompensation),
            )
        }
    }
}
