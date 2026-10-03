package com.example.dayrecorder.phone.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubPhoneUpdateClientTest {
  @Test
  fun `parses phone release APK and checksum`() {
    val release = GitHubPhoneUpdateClient.parseRelease(
      """
      {
        "draft": false,
        "prerelease": false,
        "body": "Phone updater release",
        "assets": [
          {
            "name": "day-recorder-watch-v1.7-build9-release.apk",
            "browser_download_url": "https://example.test/watch.apk"
          },
          {
            "name": "day-recorder-phone-v1.1-build2-release.apk",
            "size": 654321,
            "browser_download_url": "https://example.test/phone.apk"
          },
          {
            "name": "day-recorder-phone-v1.1-build2-release.apk.sha256",
            "browser_download_url": "https://example.test/phone.apk.sha256"
          }
        ]
      }
      """.trimIndent(),
    )

    assertEquals("1.1", release.versionName)
    assertEquals(2, release.versionCode)
    assertEquals(654321L, release.apkSize)
    assertEquals("https://example.test/phone.apk", release.apkUrl)
    assertEquals("https://example.test/phone.apk.sha256", release.checksumUrl)
  }

  @Test
  fun `rejects a non HTTPS release asset`() {
    val error = runCatching {
      GitHubPhoneUpdateClient.parseRelease(
        """
        {
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "day-recorder-phone-v1.1-build2-release.apk",
              "browser_download_url": "http://example.test/phone.apk"
            },
            {
              "name": "day-recorder-phone-v1.1-build2-release.apk.sha256",
              "browser_download_url": "https://example.test/phone.apk.sha256"
            }
          ]
        }
        """.trimIndent(),
      )
    }.exceptionOrNull()

    org.junit.Assert.assertTrue(error is IllegalStateException)
    org.junit.Assert.assertTrue(error?.message.orEmpty().contains("HTTPS"))
  }
}
