package dev.phonecam.encoder

/** What the camera captures and how the encoder compresses it. */
data class VideoConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2000,
    /** Shorter heals lost packets sooner but costs bitrate (a key frame is a full picture). */
    val keyFrameIntervalSec: Float = 0.5f,
    val frontCamera: Boolean = false,
)
