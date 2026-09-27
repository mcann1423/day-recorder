package com.example.dayrecorder.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class UpdateRelease(
  val versionName: String,
  val versionCode: Long,
  val releaseNotes: String,
  val apkName: String,
  val apkUrl: String,
  val checksumUrl: String,
)

sealed interface UpdateCheckResult {
  data class Available(val release: UpdateRelease) : UpdateCheckResult
  data class Current(val latestVersionName: String) : UpdateCheckResult
}

class GitHubUpdateClient(private val context: Context) {
  fun check(currentVersionCode: Long): UpdateCheckResult {
    val json = readText(LATEST_RELEASE_URL, MAX_METADATA_BYTES)
    val release = parseRelease(json)
    return if (release.versionCode > currentVersionCode) {
      UpdateCheckResult.Available(release)
    } else {
      UpdateCheckResult.Current(release.versionName)
    }
  }

  fun downloadAndVerify(release: UpdateRelease): File {
    val updateDirectory = File(context.cacheDir, "updates").also { directory ->
      directory.mkdirs()
      directory.listFiles()?.forEach(File::delete)
    }
    val expectedHash = readText(release.checksumUrl, MAX_CHECKSUM_BYTES)
      .trim()
      .substringBefore(' ')
      .lowercase()
    check(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Release checksum is invalid" }

    val apk = File(updateDirectory, release.apkName)
    download(release.apkUrl, apk, MAX_APK_BYTES)
    check(sha256(apk) == expectedHash) { "Downloaded APK checksum did not match" }
    check(signingDigests(archivePackageInfo(apk)) == signingDigests(installedPackageInfo())) {
      "Downloaded APK is not signed by the installed app"
    }
    return apk
  }

  fun requestInstall(apk: File): InstallRequestResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
      context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      return InstallRequestResult.PermissionRequired
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
      Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return InstallRequestResult.Launched
  }

  private fun installedPackageInfo(): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

  private fun archivePackageInfo(apk: File): PackageInfo {
    val info = if (Build.VERSION.SDK_INT >= 33) {
      context.packageManager.getPackageArchiveInfo(
        apk.absolutePath,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }
    return checkNotNull(info) { "Downloaded file is not a valid APK" }
  }

  private fun signingDigests(info: PackageInfo): Set<String> =
    checkNotNull(info.signingInfo) { "APK has no signing information" }
      .apkContentsSigners
      .mapTo(linkedSetOf()) { signer -> sha256(signer.toByteArray()) }

  companion object {
    private const val LATEST_RELEASE_URL =
      "https://api.github.com/repos/mcann1423/day-recorder/releases/latest"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val MAX_METADATA_BYTES = 512 * 1_024L
    private const val MAX_CHECKSUM_BYTES = 4 * 1_024L
    private const val MAX_APK_BYTES = 100 * 1_024 * 1_024L
    private val APK_NAME = Regex("day-recorder-watch-v([0-9.]+)-build([0-9]+)-release\\.apk")

    fun parseRelease(json: String): UpdateRelease {
      val root = JSONObject(json)
      check(!root.optBoolean("draft", true)) { "Latest release is still a draft" }
      check(!root.optBoolean("prerelease", true)) { "Latest release is a pre-release" }
      val assets = root.getJSONArray("assets")
      var apkName: String? = null
      var apkUrl: String? = null
      var checksumUrl: String? = null
      var versionName: String? = null
      var versionCode: Long? = null

      for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        val name = asset.getString("name")
        val match = APK_NAME.matchEntire(name)
        if (match != null) {
          apkName = name
          apkUrl = asset.getString("browser_download_url")
          versionName = match.groupValues[1]
          versionCode = match.groupValues[2].toLong()
        }
      }
      val requiredApkName = checkNotNull(apkName) { "Release has no compatible watch APK" }
      for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        if (asset.getString("name") == "$requiredApkName.sha256") {
          checksumUrl = asset.getString("browser_download_url")
          break
        }
      }
      return UpdateRelease(
        versionName = checkNotNull(versionName),
        versionCode = checkNotNull(versionCode),
        releaseNotes = root.optString("body").trim().take(600),
        apkName = requiredApkName,
        apkUrl = checkNotNull(apkUrl),
        checksumUrl = checkNotNull(checksumUrl) { "Release has no APK checksum" },
      )
    }

    private fun readText(url: String, maxBytes: Long): String =
      connect(url).useConnection { connection ->
        val bytes = connection.inputStream.use { input ->
          val output = java.io.ByteArrayOutputStream()
          input.copyToLimited(output, maxBytes)
          output.toByteArray()
        }
        bytes.toString(Charsets.UTF_8)
      }

    private fun download(url: String, destination: File, maxBytes: Long) {
      connect(url).useConnection { connection ->
        connection.inputStream.use { input ->
          destination.outputStream().use { output -> input.copyToLimited(output, maxBytes) }
        }
      }
    }

    private fun connect(url: String): HttpURLConnection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Day-Recorder-Wear-Updater")
        connect()
        check(responseCode in 200..299) { "Update server returned HTTP $responseCode" }
      }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
      try {
        block(this)
      } finally {
        disconnect()
      }

    private fun java.io.InputStream.copyToLimited(output: java.io.OutputStream, maxBytes: Long) {
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var total = 0L
      while (true) {
        val count = read(buffer)
        if (count < 0) return
        total += count
        check(total <= maxBytes) { "Downloaded file is larger than allowed" }
        output.write(buffer, 0, count)
      }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
      digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
  }
}

enum class InstallRequestResult {
  Launched,
  PermissionRequired,
}
