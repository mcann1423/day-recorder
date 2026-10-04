package com.example.dayrecorder.updatecore

import java.net.URI

object GitHubReleaseUrlPolicy {
  val trustedTransferHosts: Set<String> = setOf(
    "api.github.com",
    "github.com",
    "release-assets.githubusercontent.com",
  )

  fun requireRepositoryAsset(url: String, owner: String, repository: String): String {
    val uri = URI(url)
    check(uri.scheme.equals("https", ignoreCase = true)) { "Release URL must use HTTPS" }
    check(uri.host.equals("github.com", ignoreCase = true)) { "Release URL must use GitHub" }
    check(uri.port == -1 && uri.userInfo == null) { "Release URL has unexpected authority information" }
    val expectedPrefix = "/$owner/$repository/releases/download/"
    check(uri.rawPath?.startsWith(expectedPrefix) == true) { "Release URL is outside the expected repository" }
    return url
  }
}
