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
            "size": 123456,
            "browser_download_url": "https://github.com/mcann1423/day-recorder/releases/download/v1.6/day-recorder-watch-v1.6-build8-release.apk"
          },
          {
            "name": "day-recorder-watch-v1.6-build8-release.apk.sha256",
            "browser_download_url": "https://github.com/mcann1423/day-recorder/releases/download/v1.6/day-recorder-watch-v1.6-build8-release.apk.sha256"
          }
        ]
      }
      """.trimIndent(),
    )

    assertEquals("1.6", release.versionName)
    assertEquals(8, release.versionCode)
    assertEquals(123456L, release.apkSize)
    assertEquals(
      "https://github.com/mcann1423/day-recorder/releases/download/v1.6/day-recorder-watch-v1.6-build8-release.apk",
      release.apkUrl,
    )
    assertEquals(
      "https://github.com/mcann1423/day-recorder/releases/download/v1.6/day-recorder-watch-v1.6-build8-release.apk.sha256",
      release.checksumUrl,
    )
  }

  @Test
  fun `rejects a non HTTPS release asset`() {
    val error = runCatching {
      GitHubUpdateClient.parseRelease(
        """
        {
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "day-recorder-watch-v1.6-build8-release.apk",
              "browser_download_url": "http://example.test/watch.apk"
            },
            {
              "name": "day-recorder-watch-v1.6-build8-release.apk.sha256",
              "browser_download_url": "https://github.com/mcann1423/day-recorder/releases/download/v1.6/day-recorder-watch-v1.6-build8-release.apk.sha256"
            }
          ]
        }
        """.trimIndent(),
      )
    }.exceptionOrNull()

    org.junit.Assert.assertTrue(error is IllegalStateException)
    org.junit.Assert.assertTrue(error?.message.orEmpty().contains("HTTPS"))
  }

  @Test
  fun `rejects a release asset outside the expected repository`() {
    val error = runCatching {
      GitHubUpdateClient.parseRelease(
        """
        {
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "day-recorder-watch-v1.6-build8-release.apk",
              "browser_download_url": "https://github.com/attacker/day-recorder/releases/download/v1.6/watch.apk"
            },
            {
              "name": "day-recorder-watch-v1.6-build8-release.apk.sha256",
              "browser_download_url": "https://github.com/mcann1423/day-recorder/releases/download/v1.6/watch.apk.sha256"
            }
          ]
        }
        """.trimIndent(),
      )
    }.exceptionOrNull()

    org.junit.Assert.assertTrue(error is IllegalStateException)
    org.junit.Assert.assertTrue(error?.message.orEmpty().contains("expected repository"))
  }
}
