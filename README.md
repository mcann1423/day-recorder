# Day Recorder

An all-day audio recorder for a Galaxy Watch 6, plus its Android phone
companion. One morning activation starts a microphone foreground service for up
to ten hours; finalized recordings transfer safely to the phone.

## Project modules

- `app`: the Wear OS recorder.
- `phone`: the Android companion that verifies, stores, and acknowledges audio
  transferred from the watch.

Installable debug APKs are published as assets on this repository's GitHub
Releases page. They are kept out of Git history because they are generated build
artifacts.

## Current behavior

- **Start day**, **Pause/Resume**, and **End day** controls.
- Provides a **Start Day Recorder** watch-face complication. A tap starts
  recording immediately when microphone permission has already been granted;
  otherwise it opens the app to request permission. The complication displays
  **REC** while the recorder is active or paused.
- Records 16 kHz mono AAC-LC at 24 kbps directly through Android's platform
  `MediaRecorder` pipeline, avoiding application-level PCM processing.
- Uses fixed fifteen-minute chunks, avoiding high-frequency amplitude polling.
- Closes the current chunk on pause or stop so completed audio remains playable.
- Writes active audio under a `.partial` filename and exposes it as `.m4a` only
  after the file has been finalized, preventing transfer of a changing file.
- Keeps a visible foreground-service notification while recording, without an
  application-level partial wake lock.
- Stops automatically ten hours after the session starts.
- Checks battery level once every two minutes and gracefully finalizes the active
  chunk, queues a final transfer, and stops at 20%.
- Defers all watch-to-phone transfers until End Day, the ten-hour limit, an
  error, or the automatic 20% battery cutoff.
- Uses non-urgent Wear OS Data Layer assets so Google Play services can batch
  delivery for better battery life. Ending the day queues the final chunk
  immediately.
- The phone verifies file length and SHA-256 before acknowledging receipt. The
  watch deletes its copy only after receiving that acknowledgement.

Transcription and voice-only retention are outside this milestone.

## Build

### Versioning

- `versionName` is the release version shown to people. Increment its point
  version only for a substantial feature or major milestone.
- Increment the major version only when the app's core functionality is
  significantly re-architected.
- `versionCode` is the monotonically increasing build number. Increment it for
  every distributed build, including fixes, visual changes, and other minor
  revisions that do not justify a release-version change.
- The watch Settings screen displays both values, for example **Version 1.3,
  Build 5**.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
phone/build/outputs/apk/debug/phone-debug.apk
```

## Install on a watch over Wi-Fi

On the watch, enable developer mode, then enable **ADB debugging** and
**Wireless debugging**. Under **Pair new device**, note the pairing address and
code. Also note the separate device address on the main Wireless debugging page.

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEVICE_PORT
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first launch requests microphone and notification permission. Tap **Start
day**, then return to the watch face. The persistent notification confirms that
capture remains active.

To start future sessions from the watch face, edit the current watch face, pick
an available complication slot, and choose **Start Day Recorder**. The exact
watch-face editing gesture depends on the selected Samsung watch face.

## Install the phone companion

Enable USB debugging on the Galaxy S24 Ultra, connect it to this computer, and
accept the debugging prompt on the phone. Then identify the phone serial and
install the companion explicitly (the `-s` avoids the watch/phone ambiguity):

```bash
adb devices -l
adb -s PHONE_SERIAL install -r phone/build/outputs/apk/debug/phone-debug.apk
```

Open **Day Recorder** once on the phone. Received chunks appear in:

```text
Internal storage/Music/Day Recorder/YYYY-MM-DD/
```

The phone app shows the number of verified files and the latest filename. The
watch screen shows files waiting for acknowledgement and the acknowledged
count. A disconnected phone is safe: Data Layer retains queued assets and
synchronizes them when the devices reconnect.

The watch and phone APKs must use the same application ID and signing key. The
two debug APKs produced by this project already satisfy that requirement.

## Pull test recordings

Recordings are stored in the app-specific external directory on the watch. They
are removed if the app is uninstalled, so pull important tests before replacing
or uninstalling it.

```bash
adb shell ls -lh /sdcard/Android/data/com.example.dayrecorder/files/Music/DayRecorder
adb pull /sdcard/Android/data/com.example.dayrecorder/files/Music/DayRecorder ./watch-recordings
```

## First device test

1. Charge the watch and note its starting percentage.
2. Start a session and make sure the notification remains after the screen
   turns off.
3. Speak periodically for at least sixteen minutes.
4. Confirm the app reports one completed chunk.
5. Stop the session, pull the files, and play every M4A from start to finish.
6. Record the elapsed time and battery percentage used.

Do not rely on this prototype for irreplaceable recordings until the device test
has confirmed clean chunk boundaries and reliable playback on the Watch 6.
