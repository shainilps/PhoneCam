# PhoneCam

**Use your Android phone as a wireless webcam that doesn't freeze.**

Most phone-webcam apps freeze or go black when the Wi-Fi hiccups, because they wait for every
lost bit of video to be resent. PhoneCam just skips what was lost and keeps going: you may see
a small glitch for a moment, but the picture never stops.

<sub>Under the hood: hardware H.264, served as a standard RTSP stream with RTP over UDP. Works
with ffmpeg, VLC, OBS and anything else that opens `rtsp://` URLs; no extra software needed on
the computer.</sub>

## Quick start

1. Download `PhoneCam-v1.0.apk` from the
   [latest release](https://github.com/shainilps/PhoneCam/releases/latest) on the phone and open
   it. Android will ask you to allow installing apps from your browser or file manager. (Or
   [build it yourself](#build-from-source).)
2. Open PhoneCam and tap **Start streaming**. It shows a URL like `rtsp://192.168.1.23:8554/cam`.
3. Preview it on your computer (same Wi-Fi). `ffplay` comes with ffmpeg and opens the stream
   in a window, which is a quick way to check that everything works before using it as a webcam:

   ```
   ffplay -fflags nobuffer -flags low_delay -framedrop -max_delay 0 -reorder_queue_size 0 \
     -rtsp_transport udp rtsp://<phone-ip>:8554/cam
   ```

   The flags turn off buffering so the preview is close to real time. Close the window (or
   press `q`) to stop; the phone's camera turns off about 10 s later.

The camera only turns on while someone is watching, and keeps working with the screen off.

## Use it in Google Meet, Zoom, … (Linux)

```
sudo modprobe v4l2loopback video_nr=11 card_label=PhoneCam exclusive_caps=1   # once per boot
ffmpeg -fflags nobuffer -flags low_delay -max_delay 0 -rtsp_transport udp \
  -i rtsp://<phone-ip>:8554/cam -vf format=yuv420p -f v4l2 /dev/video11      # keep running
```

Then reload the meeting page and pick the **PhoneCam** camera. Holding the phone upright? Use
`-vf transpose=1,format=yuv420p`.

The `modprobe` line only lasts until reboot. If you'd rather have the camera device created
at every boot, you can set that up yourself (optional):

```
echo v4l2loopback | sudo tee /etc/modules-load.d/v4l2loopback.conf
echo "options v4l2loopback video_nr=11 card_label=PhoneCam exclusive_caps=1" | \
  sudo tee /etc/modprobe.d/v4l2loopback.conf
```

If the module is already loaded (OBS loads it for its virtual camera), new options only apply
after `sudo modprobe -r v4l2loopback` or a reboot.

**Over USB instead:** `adb forward tcp:8554 tcp:8554`, then use `rtsp://127.0.0.1:8554/cam`
with `-rtsp_transport tcp`.

## Settings

- **Resolution / frame rate / bitrate:** 640×480 at 24 fps and 500–1000 kbps is plenty for a
  video call.
- **Key frame every:** how fast a glitch heals (default 0.5 s).
- **Camera:** the back camera is usually sharper and brighter.
- **Brightness:** raise it in a dim room (brighter, but grainier).
- **Packet size:** leave at 1400 unless you're testing.

## If something's off

- **Meeting app doesn't see the camera:** start the ffmpeg command first, then reload the page.
- **Dark picture:** put a light in front of you, use the back camera, or raise Brightness.
- **Delay:** use the low-delay flags above; most delay is the player buffering.
- **Stops with the screen off:** allow PhoneCam to run in the background (*Battery → No
  restrictions*).
- **Logs:** `adb logcat -s PhoneCam/Encoder PhoneCam/Rtsp PhoneCam/Rtp PhoneCam/Service`

## Build from source

You need:
- **JDK 17 or newer** to run Gradle, e.g. [Eclipse Temurin](https://adoptium.net/). If your JDK
  isn't 17, Gradle downloads a JDK 17 for the build by itself.
- **Android SDK**: easiest through [Android Studio](https://developer.android.com/studio), or the
  [command-line tools](https://developer.android.com/tools/sdkmanager) alone. Accept the SDK
  licenses once (`sdkmanager --licenses`) so Gradle can fetch the parts it needs (Android 36
  platform and build tools).

Point the build at your SDK with `ANDROID_HOME`, or a `local.properties` file containing
`sdk.dir=/path/to/Android/Sdk`, then:

```
./gradlew assembleDebug    # APK in app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug     # or build and install on a phone connected over USB
```

For `installDebug`, turn on *Developer options → USB debugging* on the phone. On Xiaomi phones,
also turn off *Developer options → MIUI optimization*, or the install fails with
`INSTALL_FAILED_USER_RESTRICTED`.

## Note

There's no password: anyone on your network can open the stream. Use it on networks you
trust, and stop it when you're done.

Requires Android 9+. No audio.
