package com.example.dayrecorder.recording

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.dayrecorder.transfer.TransferProtocol
import com.example.dayrecorder.transfer.WatchTransferQueue
import com.google.android.gms.wearable.Wearable
import java.io.File

class RecordingRetentionManager(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(RecorderContract.PREFS, Context.MODE_PRIVATE)
  private val policy = RecordingRetentionPolicy()

  fun maintain(excludedFileName: String? = null, now: Long = System.currentTimeMillis()): RetentionSnapshot {
    val directory = recordingDirectory()
    val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
    val plan = policy.evaluate(
      files.map { RetentionFile(it.name, it.length(), it.lastModified()) },
      now,
      excludedFileName,
    )
    return applyDeletions(files, plan.deleteNames, "Expired by retention policy", excludedFileName, now)
  }

  fun purgeAll(excludedFileName: String? = null, now: Long = System.currentTimeMillis()): RetentionSnapshot {
    val files = recordingDirectory().listFiles()?.filter { it.isFile }.orEmpty()
    val deleteNames = files
      .filter { it.name != excludedFileName }
      .filter { it.name.endsWith(".m4a") || it.name.endsWith(".m4a.partial") }
      .mapTo(linkedSetOf()) { it.name }
    return applyDeletions(files, deleteNames, "Purged by user", excludedFileName, now)
  }

  private fun applyDeletions(
    files: List<File>,
    requestedNames: Set<String>,
    reason: String,
    excludedFileName: String?,
    now: Long,
  ): RetentionSnapshot {
    val deletedNames = linkedSetOf<String>()
    files
      .filter { it.name in requestedNames && it.name != excludedFileName }
      .forEach { file -> if (file.delete()) deletedNames += file.name }

    if (deletedNames.isNotEmpty()) {
      val queued = prefs.getStringSet(WatchTransferQueue.KEY_QUEUED_NAMES, emptySet()).orEmpty().toMutableSet()
      deletedNames.filter { it.endsWith(".m4a") }.forEach { name ->
        queued.remove(name)
        Wearable.getDataClient(appContext).deleteDataItems(
          Uri.parse("wear://*${TransferProtocol.AUDIO_PREFIX}${TransferProtocol.idFor(name)}"),
        )
      }
      prefs.edit()
        .putStringSet(WatchTransferQueue.KEY_QUEUED_NAMES, queued)
        .putInt(RecorderContract.KEY_QUEUED_COUNT, queued.size)
        .putInt(
          RecorderContract.KEY_RETENTION_PURGED_COUNT,
          prefs.getInt(RecorderContract.KEY_RETENTION_PURGED_COUNT, 0) + deletedNames.size,
        )
        .putString(RecorderContract.KEY_LAST_RETENTION_ACTION, "$reason: ${deletedNames.size} file(s)")
        .apply()
    }

    val remaining = recordingDirectory().listFiles()
      ?.filter { it.isFile && (it.name.endsWith(".m4a") || it.name.endsWith(".m4a.partial")) }
      .orEmpty()
    val retainedFinalized = remaining.filter { it.name.endsWith(".m4a") }
    val warningCount = retainedFinalized.count {
      (now - it.lastModified()).coerceAtLeast(0L) >= RecordingRetentionPolicy.WARNING_AGE_MS
    }
    val snapshot = RetentionSnapshot(
      pendingBytes = remaining.sumOf { it.length() },
      oldestPendingAt = retainedFinalized.minOfOrNull { it.lastModified() } ?: 0L,
      warningCount = warningCount,
      deletedCount = deletedNames.size,
    )
    prefs.edit()
      .putLong(RecorderContract.KEY_PENDING_BYTES, snapshot.pendingBytes)
      .putLong(RecorderContract.KEY_OLDEST_PENDING_AT, snapshot.oldestPendingAt)
      .putInt(RecorderContract.KEY_RETENTION_WARNING_COUNT, snapshot.warningCount)
      .apply()
    return snapshot
  }

  private fun recordingDirectory(): File =
    File(appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DayRecorder").also {
      if (!it.exists()) it.mkdirs()
    }
}

data class RetentionSnapshot(
  val pendingBytes: Long,
  val oldestPendingAt: Long,
  val warningCount: Int,
  val deletedCount: Int,
)
