package dev.phonecam.service

import dev.phonecam.encoder.VideoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamUrl(val url: String, val network: String)

/** What the service is doing, for the UI to show. */
data class StreamStatus(
    val running: Boolean = false,
    val config: VideoConfig? = null,
    val maxPacketSize: Int = 0,
    val urls: List<StreamUrl> = emptyList(),
    val clients: Int = 0,
    val error: String? = null,
)

object StreamState {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    internal fun update(change: (StreamStatus) -> StreamStatus) = _status.update(change)
}
