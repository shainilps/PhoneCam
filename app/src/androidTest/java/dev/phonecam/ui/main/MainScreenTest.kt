package dev.phonecam.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.phonecam.encoder.VideoConfig
import dev.phonecam.service.StreamStatus
import dev.phonecam.service.StreamUrl
import org.junit.Rule
import org.junit.Test

/** UI tests for [MainScreenContent]. */
class MainScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stopped_showsStartButton() {
        composeTestRule.setContent { MainScreenContent(StreamStatus(), VideoConfig(), 1400, false, {}, {}, {}, {}) }
        composeTestRule.onNodeWithText("Start streaming").assertExists()
    }

    @Test
    fun streaming_showsUrlAndStopButton() {
        val status = StreamStatus(running = true, config = VideoConfig(), urls = listOf(StreamUrl(URL, "wlan0")))
        composeTestRule.setContent { MainScreenContent(status, VideoConfig(), 1400, false, {}, {}, {}, {}) }
        composeTestRule.onNodeWithText(URL).assertExists()
        composeTestRule.onNodeWithText("Stop").assertExists()
    }
}

private const val URL = "rtsp://192.168.1.5:8554/cam"
