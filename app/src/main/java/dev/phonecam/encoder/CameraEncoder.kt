package dev.phonecam.encoder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer

/** Receives the encoder's output. Buffers are only valid during the call. */
interface EncodedVideoSink {
    fun onParameterSets(sps: ByteArray, pps: ByteArray)

    /** One encoded frame as NAL units without start codes (SPS/PPS/AUD removed). */
    fun onFrame(nals: List<ByteBuffer>, ptsUs: Long, isKeyFrame: Boolean)

    fun onError(message: String)
}

/**
 * Camera2 straight into the hardware H.264 encoder through its input Surface, so pixels never
 * touch the CPU. All camera and codec callbacks run on one background thread.
 */
class CameraEncoder(
    private val context: Context,
    private val config: VideoConfig,
    private val sink: EncodedVideoSink,
) {
    private val thread = HandlerThread("camera-encoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var stopped = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val nals = ArrayList<ByteBuffer>(8)

    // Stats, logged every few seconds.
    private var statsStart = 0L
    private var statsFrames = 0
    private var statsKeyFrames = 0
    private var statsBytes = 0L
    private var lastPtsUs = 0L
    private var maxGapUs = 0L

    fun start() {
        handler.post {
            try {
                startCodec()
                openCamera()
            } catch (e: Exception) {
                fail("Start failed: ${e.message}", e)
            }
        }
    }

    fun stop() {
        handler.post {
            stopped = true
            releaseAll()
            thread.quitSafely()
        }
    }

    /** Asks the encoder for an IDR frame as soon as possible (e.g. a new client joined). */
    fun requestKeyFrame() {
        handler.post {
            val c = codec ?: return@post
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                Log.d(TAG, "Key frame requested")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Key frame request failed", e)
            }
        }
    }

    // ---- Encoder ----

    private fun startCodec() {
        val c = MediaCodec.createEncoderByType(MIME)
        Log.i(TAG, "Encoder: ${c.name}")
        val caps = c.codecInfo.getCapabilitiesForType(MIME)
        val video = caps.videoCapabilities
        if (!video.isSizeSupported(config.width, config.height)) {
            c.release()
            throw IllegalArgumentException(
                "Encoder can't do ${config.width}x${config.height} " +
                    "(widths ${video.supportedWidths}, heights ${video.supportedHeights})"
            )
        }
        Log.i(TAG, "Encoder bitrate range ${video.bitrateRange}, profiles " +
            caps.profileLevels.joinToString { "${it.profile}/${it.level}" })

        c.setCallback(codecCallback, handler)
        val cbr = caps.encoderCapabilities.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )
        try {
            c.configure(buildFormat(tuned = true, cbr = cbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some vendor encoders reject profile/level/mode hints; fall back to the basics.
            Log.w(TAG, "Tuned encoder config rejected, retrying with basic config", e)
            c.reset()
            c.setCallback(codecCallback, handler)
            c.configure(buildFormat(tuned = false, cbr = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        statsStart = SystemClock.elapsedRealtime()
        Log.i(TAG, "Encoder started: $config cbr=$cbr")
    }

    private fun buildFormat(tuned: Boolean, cbr: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSec) // float since API 25
            if (tuned) {
                // Baseline: no B-frames, so every frame can be sent the moment it is encoded.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
                if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: the camera fills the encoder directly.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        readParameterSets(buf, info.offset, info.offset + info.size)
                    } else {
                        deliverFrame(buf, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                if (!stopped) Log.w(TAG, "Output buffer handling failed", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            fail("Encoder error: ${e.diagnosticInfo}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Encoder output format: $format")
            // Some encoders report SPS/PPS here instead of in a codec-config buffer.
            listOf("csd-0", "csd-1").forEach { key ->
                format.getByteBuffer(key)?.let { readParameterSets(it, it.position(), it.limit()) }
            }
        }
    }

    private fun readParameterSets(buf: ByteBuffer, from: Int, to: Int) {
        forEachNal(buf, from, to) { start, end -> storeIfParameterSet(buf, start, end) }
    }

    private fun storeIfParameterSet(buf: ByteBuffer, start: Int, end: Int): Boolean {
        val type = nalType(buf.get(start))
        if (type != NAL_SPS && type != NAL_PPS) return false
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = buf.get(start + i)
        if (type == NAL_SPS) {
            if (!bytes.contentEquals(sps)) { sps = bytes; Log.i(TAG, "SPS ${bytes.toHex()}") } else return true
        } else {
            if (!bytes.contentEquals(pps)) { pps = bytes; Log.i(TAG, "PPS ${bytes.toHex()}") } else return true
        }
        val s = sps
        val p = pps
        if (s != null && p != null) sink.onParameterSets(s, p)
        return true
    }

    private fun deliverFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        nals.clear()
        var key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        forEachNal(buf, info.offset, info.offset + info.size) { start, end ->
            val type = nalType(buf.get(start))
            when {
                storeIfParameterSet(buf, start, end) -> Unit // sent by the server before each key frame
                type == NAL_AUD -> Unit // carries nothing a decoder needs
                else -> {
                    if (type == NAL_IDR) key = true
                    val nal = buf.duplicate()
                    nal.limit(end)
                    nal.position(start)
                    nals.add(nal)
                }
            }
        }
        if (nals.isEmpty()) return
        sink.onFrame(nals, info.presentationTimeUs, key)
        updateStats(info, key)
    }

    private fun updateStats(info: MediaCodec.BufferInfo, key: Boolean) {
        statsFrames++
        statsBytes += info.size
        if (key) statsKeyFrames++
        if (lastPtsUs != 0L) maxGapUs = maxOf(maxGapUs, info.presentationTimeUs - lastPtsUs)
        lastPtsUs = info.presentationTimeUs
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsStart
        if (elapsed >= STATS_INTERVAL_MS) {
            Log.i(TAG, "Encoded %.1f fps, %d kbps, %d key frames, avg %d B/frame, max gap %d ms".format(
                statsFrames * 1000f / elapsed, statsBytes * 8 / elapsed, statsKeyFrames,
                statsBytes / statsFrames, maxGapUs / 1000))
            statsStart = now
            statsFrames = 0
            statsKeyFrames = 0
            statsBytes = 0
            maxGapUs = 0
        }
    }

    // ---- Camera ----

    @SuppressLint("MissingPermission") // checked just below
    private fun openCamera() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Camera permission not granted")
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val facing = if (config.frontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.first()
        val chars = manager.getCameraCharacteristics(id)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaCodec::class.java).orEmpty()
        if (sizes.none { it == Size(config.width, config.height) }) {
            Log.w(TAG, "Camera $id may not support ${config.width}x${config.height} for video; sizes: ${sizes.joinToString()}")
        }
        val fpsRange = pickFpsRange(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty())
        Log.i(TAG, "Opening camera $id (front=${config.frontCamera}), fps range $fpsRange")

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped) { device.close(); return }
                camera = device
                startSession(device, fpsRange)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
                fail("Camera disconnected (another app took it?)", null)
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                fail("Camera error $error", null)
            }
        }, handler)
    }

    /** A fixed range like [30, 30] keeps the frame rate steady instead of dropping in low light. */
    private fun pickFpsRange(ranges: Array<out Range<Int>>): Range<Int>? {
        Log.i(TAG, "Camera fps ranges: ${ranges.joinToString()}")
        return ranges.firstOrNull { it.lower == config.fps && it.upper == config.fps }
            ?: ranges.filter { it.upper == config.fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= config.fps }.minByOrNull { it.upper }
    }

    @Suppress("DEPRECATION") // the List<Surface> overload is the one available on API 28
    private fun startSession(device: CameraDevice, fpsRange: Range<Int>?) {
        val surface = inputSurface ?: return
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (stopped) { s.close(); return }
                session = s
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }.build()
                s.setRepeatingRequest(request, null, handler)
                Log.i(TAG, "Camera streaming into encoder")
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                fail("Camera session configuration failed (unsupported size ${config.width}x${config.height}?)", null)
            }
        }, handler)
    }

    // ---- Teardown ----

    private fun fail(message: String, e: Throwable?) {
        Log.e(TAG, message, e)
        if (stopped) return
        stopped = true
        releaseAll()
        sink.onError(message)
    }

    private fun releaseAll() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        Log.i(TAG, "Camera and encoder released")
    }

    private companion object {
        const val TAG = "PhoneCam/Encoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val STATS_INTERVAL_MS = 5000L
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
