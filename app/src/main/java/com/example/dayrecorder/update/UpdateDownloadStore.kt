package com.example.dayrecorder.update

import android.content.Context
import com.example.dayrecorder.updatecore.UpdateTransferProgress
import java.util.UUID

enum class UpdateDownloadPhase {
  IDLE,
  DOWNLOADING,
  READY,
  ERROR,
}

data class UpdateDownloadStatus(
  val phase: UpdateDownloadPhase = UpdateDownloadPhase.IDLE,
  val versionCode: Long = 0L,
  val apkName: String = "",
  val message: String = "",
) {
  val isBusy: Boolean
    get() = phase == UpdateDownloadPhase.DOWNLOADING

  fun isFor(release: UpdateRelease): Boolean =
    versionCode == release.versionCode && apkName == release.apkName

  fun isReadyFor(release: UpdateRelease): Boolean =
    phase == UpdateDownloadPhase.READY && isFor(release)
}

class UpdateDownloadStore(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun snapshot(): UpdateDownloadStatus {
    val status = UpdateDownloadStatus(
      phase = runCatching {
        UpdateDownloadPhase.valueOf(prefs.getString(KEY_PHASE, UpdateDownloadPhase.IDLE.name).orEmpty())
      }.getOrDefault(UpdateDownloadPhase.IDLE),
      versionCode = prefs.getLong(KEY_VERSION_CODE, 0L),
      apkName = prefs.getString(KEY_APK_NAME, "").orEmpty(),
      message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
    )
    if (
      status.phase == UpdateDownloadPhase.DOWNLOADING &&
        prefs.getString(KEY_PROCESS_TOKEN, "") != PROCESS_TOKEN
    ) {
      val recovered = status.copy(
        phase = UpdateDownloadPhase.ERROR,
        message = "Update download was interrupted. Tap Install to resume.",
      )
      write(recovered, durable = true)
      return recovered
    }
    return status
  }

  fun clearIfInstalled(installedVersionCode: Long): Boolean {
    val status = snapshot()
    if (status.versionCode !in 1..installedVersionCode) return false
    clear()
    return true
  }

  fun markStarting(release: UpdateRelease) {
    write(release, UpdateDownloadPhase.DOWNLOADING, "Preparing download…")
  }

  fun markProgress(release: UpdateRelease, progress: UpdateTransferProgress) {
    write(release, UpdateDownloadPhase.DOWNLOADING, progress.displayText())
  }

  fun markReady(release: UpdateRelease) {
    write(release, UpdateDownloadPhase.READY, "Update verified. Tap Install again to continue.", durable = true)
  }

  fun markError(release: UpdateRelease, message: String) {
    write(release, UpdateDownloadPhase.ERROR, message, durable = true)
  }

  fun clear() {
    prefs.edit().clear().commit()
  }

  private fun write(
    release: UpdateRelease,
    phase: UpdateDownloadPhase,
    message: String,
    durable: Boolean = false,
  ) {
    write(
      UpdateDownloadStatus(
        phase = phase,
        versionCode = release.versionCode,
        apkName = release.apkName,
        message = message,
      ),
      durable,
    )
  }

  private fun write(status: UpdateDownloadStatus, durable: Boolean) {
    val editor = prefs.edit()
      .putString(KEY_PHASE, status.phase.name)
      .putLong(KEY_VERSION_CODE, status.versionCode)
      .putString(KEY_APK_NAME, status.apkName)
      .putString(KEY_MESSAGE, status.message)
      .putString(KEY_PROCESS_TOKEN, PROCESS_TOKEN)
    if (durable) editor.commit() else editor.apply()
  }

  companion object {
    private const val PREFS = "update-download"
    private const val KEY_PHASE = "phase"
    private const val KEY_VERSION_CODE = "version-code"
    private const val KEY_APK_NAME = "apk-name"
    private const val KEY_MESSAGE = "message"
    private const val KEY_PROCESS_TOKEN = "process-token"
    private val PROCESS_TOKEN = UUID.randomUUID().toString()
  }
}
