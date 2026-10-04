package com.example.dayrecorder.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadStatusTest {
  private val release = UpdateRelease(
    versionName = "1.8.4",
    versionCode = 14,
    releaseNotes = "",
    apkName = "day-recorder-watch-v1.8.4-build14-release.apk",
    apkUrl = "https://example.test/watch.apk",
    checksumUrl = "https://example.test/watch.apk.sha256",
    apkSize = 1024L,
  )

  @Test
  fun `only downloading state is busy`() {
    assertTrue(UpdateDownloadStatus(phase = UpdateDownloadPhase.DOWNLOADING).isBusy)
    assertFalse(UpdateDownloadStatus(phase = UpdateDownloadPhase.READY).isBusy)
    assertFalse(UpdateDownloadStatus(phase = UpdateDownloadPhase.ERROR).isBusy)
  }

  @Test
  fun `ready cache must match version code and APK name`() {
    val ready = UpdateDownloadStatus(
      phase = UpdateDownloadPhase.READY,
      versionCode = release.versionCode,
      apkName = release.apkName,
    )

    assertTrue(ready.isReadyFor(release))
    assertFalse(ready.copy(versionCode = release.versionCode + 1).isReadyFor(release))
    assertFalse(ready.copy(apkName = "other.apk").isReadyFor(release))
    assertFalse(ready.copy(phase = UpdateDownloadPhase.ERROR).isReadyFor(release))
  }
}
