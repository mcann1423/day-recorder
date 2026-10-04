package com.example.dayrecorder.updatecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUrlPolicyTest {
  @Test
  fun `accepts an asset from the expected repository`() {
    val url = "https://github.com/mcann1423/day-recorder/releases/download/v1.8.3/update.apk"

    assertEquals(url, GitHubReleaseUrlPolicy.requireRepositoryAsset(url, "mcann1423", "day-recorder"))
  }

  @Test
  fun `rejects an asset from another host`() {
    val error = runCatching {
      GitHubReleaseUrlPolicy.requireRepositoryAsset(
        "https://example.test/mcann1423/day-recorder/releases/download/v1.8.3/update.apk",
        "mcann1423",
        "day-recorder",
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error?.message.orEmpty().contains("GitHub"))
  }

  @Test
  fun `rejects an asset from another repository`() {
    val error = runCatching {
      GitHubReleaseUrlPolicy.requireRepositoryAsset(
        "https://github.com/attacker/day-recorder/releases/download/v1.8.3/update.apk",
        "mcann1423",
        "day-recorder",
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error?.message.orEmpty().contains("expected repository"))
  }
}
