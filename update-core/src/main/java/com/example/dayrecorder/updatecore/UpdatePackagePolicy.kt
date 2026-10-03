package com.example.dayrecorder.updatecore

object UpdatePackagePolicy {
  fun validate(
    expectedPackageName: String,
    expectedVersionCode: Long,
    installedVersionCode: Long,
    installedSigningDigests: Set<String>,
    archivePackageName: String,
    archiveVersionCode: Long,
    archiveSigningDigests: Set<String>,
  ) {
    check(archivePackageName == expectedPackageName) { "Downloaded APK has the wrong package name" }
    check(archiveVersionCode == expectedVersionCode) { "Downloaded APK version does not match the release" }
    check(archiveVersionCode > installedVersionCode) { "Downloaded APK is not newer than the installed app" }
    check(installedSigningDigests.isNotEmpty() && archiveSigningDigests == installedSigningDigests) {
      "Downloaded APK is not signed by the installed app"
    }
  }
}
