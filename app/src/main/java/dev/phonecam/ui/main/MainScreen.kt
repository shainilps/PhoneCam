package dev.phonecam.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecam.encoder.VideoConfig
import dev.phonecam.service.StreamService
import dev.phonecam.service.StreamState
import dev.phonecam.service.StreamStatus
import dev.phonecam.service.StreamUrl
import dev.phonecam.theme.PhoneCamTheme

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by StreamState.status.collectAsStateWithLifecycle()
    var config by rememberSaveable(stateSaver = VideoConfigSaver) { mutableStateOf(VideoConfig()) }
    var packetSize by rememberSaveable { mutableIntStateOf(StreamService.DEFAULT_PACKET_SIZE) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionDenied = result[Manifest.permission.CAMERA] != true
        if (!permissionDenied) StreamService.start(context, config, packetSize)
    }

    MainScreenContent(
        status = status,
        config = config,
        packetSize = packetSize,
        permissionDenied = permissionDenied,
        onConfigChange = { config = it },
        onPacketSizeChange = { packetSize = it },
        onStart = {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                StreamService.start(context, config, packetSize)
            } else {
                val permissions = buildList {
                    add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }
        },
        onStop = { StreamService.stop(context) },
        modifier = modifier,
    )
}

@Composable
internal fun MainScreenContent(
    status: StreamStatus,
    config: VideoConfig,
    packetSize: Int,
    permissionDenied: Boolean,
    onConfigChange: (VideoConfig) -> Unit,
    onPacketSizeChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PhoneCam", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = if (status.running) "Streaming · ${status.clients} client(s)" else "Stopped"
                Text(state, style = MaterialTheme.typography.titleMedium)
                status.config?.let {
                    Text("${it.width}x${it.height} · ${it.fps} fps · ${it.bitrateKbps} kbps · " +
                        "key frame every ${it.keyFrameIntervalSec} s · ${if (it.frontCamera) "front" else "back"} camera · " +
                        "packets ≤ ${status.maxPacketSize} B")
                }
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (permissionDenied) Text("Camera permission is needed to stream.", color = MaterialTheme.colorScheme.error)
                if (status.running) StreamUrls(status.urls)
            }
        }

        val enabled = !status.running
        ChoiceRow("Resolution", RESOLUTIONS, config.width to config.height, enabled, { "${it.first}x${it.second}" }) {
            onConfigChange(config.copy(width = it.first, height = it.second))
        }
        ChoiceRow("Frame rate", listOf(15, 24, 30), config.fps, enabled, { "$it fps" }) {
            onConfigChange(config.copy(fps = it))
        }
        ChoiceRow("Bitrate", listOf(500, 1000, 2000, 4000), config.bitrateKbps, enabled, { "$it kbps" }) {
            onConfigChange(config.copy(bitrateKbps = it))
        }
        ChoiceRow("Key frame every", listOf(0.5f, 1f, 2f), config.keyFrameIntervalSec, enabled, { "$it s" }) {
            onConfigChange(config.copy(keyFrameIntervalSec = it))
        }
        ChoiceRow("Packet size", listOf(1400, 1200, 1000, 800), packetSize, enabled, { "$it B" }, onPacketSizeChange)
        ChoiceRow("Camera", listOf(false, true), config.frontCamera, enabled, { if (it) "Front" else "Back" }) {
            onConfigChange(config.copy(frontCamera = it))
        }

        if (status.running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start streaming") }
        }
    }
}

@Composable
private fun StreamUrls(urls: List<StreamUrl>) {
    if (urls.isEmpty()) {
        Text("No network found. Connect to Wi-Fi, or use USB: adb forward tcp:8554 tcp:8554")
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            urls.forEach {
                Text(it.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                Text(it.network, style = MaterialTheme.typography.bodySmall)
            }
            Text("Test: ffplay -fflags nobuffer -flags low_delay -rtsp_transport udp ${urls.first().url}",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text(option)) },
                    enabled = enabled,
                )
            }
        }
    }
}

private val RESOLUTIONS = listOf(640 to 480, 960 to 540, 1280 to 720)

private val VideoConfigSaver = listSaver<VideoConfig, Any>(
    save = { listOf(it.width, it.height, it.fps, it.bitrateKbps, it.keyFrameIntervalSec, it.frontCamera) },
    restore = { VideoConfig(it[0] as Int, it[1] as Int, it[2] as Int, it[3] as Int, it[4] as Float, it[5] as Boolean) },
)

@Preview(showBackground = true)
@Composable
fun MainScreenStoppedPreview() {
    PhoneCamTheme {
        MainScreenContent(StreamStatus(), VideoConfig(), 1400, false, {}, {}, {}, {}, Modifier.padding(16.dp))
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenStreamingPreview() {
    PhoneCamTheme {
        MainScreenContent(
            StreamStatus(
                running = true,
                config = VideoConfig(),
                maxPacketSize = 1400,
                urls = listOf(StreamUrl("rtsp://10.40.117.130:8554/cam", "wlan0")),
                clients = 1,
            ),
            VideoConfig(), 1400, false, {}, {}, {}, {}, Modifier.padding(16.dp),
        )
    }
}
