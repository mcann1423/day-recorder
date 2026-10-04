package com.example.dayrecorder.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.dayrecorder.updatecore.GitHubReleaseUrlPolicy
import com.example.dayrecorder.updatecore.ResumableUpdateDownloader
import com.example.dayrecorder.updatecore.UpdatePackagePolicy
import com.example.dayrecorder.updatecore.UpdateTransferProgress
import com.example.dayrecorder.updatecore.UpdateTransferStage
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

data class UpdateRelease(
  val versionName: String,
  val versionCode: Long,
  val releaseNotes: String,
  val apkName: String,
  val apkUrl: String,
  val checksumUrl: String,
  val apkSize: Long?,
)

sealed interface UpdateCheckResult {
  data class Available(val release: UpdateRelease) : UpdateCheckResult
  data class Current(val latestVersionName: String) : UpdateCheckResult
}

class GitHubUpdateClient(private val context: Context) {
  private val downloader = ResumableUpdateDownloader(userAgent = "Day-Recorder-Wear-Updater")

  fun check(currentVersionCode: Long): UpdateCheckResult {
    val json = downloader.readText(LATEST_RELEASE_URL, MAX_METADATA_BYTES)
    val release = parseRelease(json)
    return if (release.versionCode > currentVersionCode) {
      UpdateCheckResult.Available(release)
    } else {
      UpdateCheckResult.Current(release.versionName)
    }
  }

  fun downloadAndVerify(
    release: UpdateRelease,
    onProgress: (UpdateTransferProgress) -> Unit = {},
  ): File {
    val updateDirectory = File(context.cacheDir, "updates").also(File::mkdirs)
    val apk = File(updateDirectory, release.apkName)
    val partial = File(updateDirectory, "${release.apkName}.part")
    val checksumCache = File(updateDirectory, "${release.apkName}.sha256")
    cleanUpdateDirectory(updateDirectory, setOf(apk.name, partial.name, checksumCache.name))

    verifiedCachedApk(release)?.let { cachedApk ->
      onProgress(UpdateTransferProgress(UpdateTransferStage.READY, cachedApk.length(), release.apkSize))
      return cachedApk
    }

    val expectedHash = downloader.readText(release.checksumUrl, MAX_CHECKSUM_BYTES)
      .trim()
      .substringBefore(' ')
      .lowercase()
    check(expectedHash.matches(SHA256)) { "Release checksum is invalid" }

    downloader.download(release.apkUrl, partial, MAX_APK_BYTES, release.apkSize, onProgress)
    onProgress(UpdateTransferProgress(UpdateTransferStage.VERIFYING, partial.length(), release.apkSize))
    if (sha256(partial) != expectedHash) {
      partial.delete()
      throw IllegalStateException("Downloaded APK checksum did not match; the partial download was discarded")
    }
    verifyDownloadedApk(partial, release, expectedHash)
    publishVerifiedApk(partial, apk)
    checksumCache.writeText(expectedHash)
    onProgress(UpdateTransferProgress(UpdateTransferStage.READY, apk.length(), release.apkSize))
    return apk
  }

  fun verifiedCachedApk(release: UpdateRelease): File? {
    val updateDirectory = File(context.cacheDir, "updates")
    val apk = File(updateDirectory, release.apkName)
    val checksumCache = File(updateDirectory, "${release.apkName}.sha256")
    val cachedHash = checksumCache.takeIf(File::isFile)?.readText()?.trim()?.lowercase()
    if (!apk.isFile || cachedHash?.matches(SHA256) != true) return null
    if (runCatching { verifyDownloadedApk(apk, release, cachedHash) }.isSuccess) return apk
    apk.delete()
    checksumCache.delete()
    return null
  }

  fun requestInstall(release: UpdateRelease, apk: File): InstallRequestResult {
    val expectedApk = File(File(context.cacheDir, "updates"), release.apkName).canonicalFile
    check(apk.canonicalFile == expectedApk) { "Refusing to install an APK outside verified update storage" }
    val checksumCache = File(expectedApk.parentFile, "${release.apkName}.sha256")
    val expectedHash = checksumCache.takeIf(File::isFile)?.readText()?.trim()?.lowercase()
    check(expectedHash?.matches(SHA256) == true) { "Verified update metadata is missing" }
    verifyDownloadedApk(expectedApk, release, expectedHash)
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

  private fun verifyDownloadedApk(apk: File, release: UpdateRelease, expectedHash: String) {
    check(sha256(apk) == expectedHash) { "Downloaded APK checksum did not match" }
    val installed = installedPackageInfo()
    val archive = archivePackageInfo(apk)
    UpdatePackagePolicy.validate(
      expectedPackageName = context.packageName,
      expectedVersionCode = release.versionCode,
      installedVersionCode = installed.longVersionCode,
      installedSigningDigests = signingDigests(installed),
      archivePackageName = archive.packageName,
      archiveVersionCode = archive.longVersionCode,
      archiveSigningDigests = signingDigests(archive),
    )
  }

  private fun publishVerifiedApk(partial: File, apk: File) {
    try {
      Files.move(
        partial.toPath(),
        apk.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun cleanUpdateDirectory(directory: File, keepNames: Set<String>) {
    val staleBefore = System.currentTimeMillis() - STALE_UPDATE_MILLIS
    directory.listFiles()?.forEach { file ->
      if (file.name !in keepNames || file.lastModified() < staleBefore) file.delete()
    }
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
    private const val STALE_UPDATE_MILLIS = 24L * 60L * 60L * 1_000L
    private val APK_NAME = Regex("day-recorder-watch-v([0-9.]+)-build([0-9]+)-release\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")

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
      var apkSize: Long? = null

      for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        val name = asset.getString("name")
        val match = APK_NAME.matchEntire(name)
        if (match != null) {
          apkName = name
          apkUrl = requireReleaseAssetUrl(asset.getString("browser_download_url"))
          versionName = match.groupValues[1]
          versionCode = match.groupValues[2].toLong()
          apkSize = asset.optLong("size").takeIf { it > 0L }
        }
      }
      val requiredApkName = checkNotNull(apkName) { "Release has no compatible watch APK" }
      for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        if (asset.getString("name") == "$requiredApkName.sha256") {
          checksumUrl = requireReleaseAssetUrl(asset.getString("browser_download_url"))
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
        apkSize = apkSize,
      )
    }

    private fun requireReleaseAssetUrl(url: String): String =
      GitHubReleaseUrlPolicy.requireRepositoryAsset(url, REPOSITORY_OWNER, REPOSITORY_NAME)

    private const val REPOSITORY_OWNER = "mcann1423"
    private const val REPOSITORY_NAME = "day-recorder"

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
