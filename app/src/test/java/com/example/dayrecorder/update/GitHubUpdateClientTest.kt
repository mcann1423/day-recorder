package com.example.dayrecorder.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubUpdateClientTest {
  @Test
  fun `parses release APK and checksum`() {
    val release = GitHubUpdateClient.parseRelease(
      """
      {
        "draft": false,
        "prerelease": false,
        "body": "Retention and updater release",
        "assets": [
          {
            "name": "day-recorder-watch-v1.6-build8-release.apk",
            "browser_download_url": "https://example.test/watch.apk"
          },
          {
            "name": "day-recorder-watch-v1.6-build8-release.apk.sha256",
            "browser_download_url": "https://example.test/watch.apk.sha256"
          }
        ]
      }
      """.trimIndent(),
    )

    assertEquals("1.6", release.versionName)
    assertEquals(8, release.versionCode)
    assertEquals("https://example.test/watch.apk", release.apkUrl)
    assertEquals("https://example.test/watch.apk.sha256", release.checksumUrl)
  }
}
