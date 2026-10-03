package com.example.dayrecorder.phone

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class AudioReceiveWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  private val prefs = applicationContext.getSharedPreferences(PhoneContract.PREFS, Context.MODE_PRIVATE)
  private val dataClient = Wearable.getDataClient(applicationContext)
  private val recordingStorage = PhoneRecordingStorage(applicationContext)

  override fun doWork(): Result {
    discardLegacyReceipts()
    var failed = false
    val items = try {
      Tasks.await(dataClient.getDataItems())
    } catch (error: Throwable) {
      saveError(error)
      return Result.retry()
    }

    try {
      items.forEach { item ->
        if (!item.uri.path.orEmpty().startsWith(TransferProtocol.AUDIO_PREFIX)) return@forEach
        if (!runCatching { receiveAndAcknowledge(DataMapItem.fromDataItem(item).dataMap) }.getOrElse {
            saveError(it)
            failed = true
            false
          }) {
          failed = true
        }
      }
    } finally {
      items.release()
    }
    return if (failed) Result.retry() else Result.success()
  }

  private fun receiveAndAcknowledge(data: DataMap): Boolean {
    val originalName = data.getString(TransferProtocol.KEY_FILE_NAME) ?: return false
    val fileName = sanitizeFileName(originalName) ?: return false
    val expectedSize = data.getLong(TransferProtocol.KEY_SIZE)
    val expectedHash = data.getString(TransferProtocol.KEY_SHA256) ?: return false
    val recordedAt = data.getLong(TransferProtocol.KEY_RECORDED_AT)

    val archiveResult = VerifiedArchivePolicy.ensureVerified(
      isArchivedAndVerified = {
        recordingStorage.hasVerifiedRecording(fileName, expectedSize, expectedHash)
      },
      store = {
        receiveAudio(data, fileName, expectedSize, expectedHash, recordedAt)
      },
    )
    if (archiveResult == VerifiedArchiveResult.Unverified) return false
    if (archiveResult == VerifiedArchiveResult.Stored) {
      prefs.edit()
        .putInt(PhoneContract.KEY_RECEIVED_COUNT, prefs.getInt(PhoneContract.KEY_RECEIVED_COUNT, 0) + 1)
        .putString(PhoneContract.KEY_LAST_FILE, fileName)
        .remove(PhoneContract.KEY_LAST_ERROR)
        .apply()
    }

    val id = fileName.removeSuffix(".m4a")
    val ack = PutDataMapRequest.create(TransferProtocol.ACK_PREFIX + id)
    ack.dataMap.apply {
      putString(TransferProtocol.KEY_FILE_NAME, fileName)
      putString(TransferProtocol.KEY_SHA256, expectedHash)
      putLong(TransferProtocol.KEY_ACKNOWLEDGED_AT, System.currentTimeMillis())
    }
    Tasks.await(dataClient.putDataItem(ack.asPutDataRequest()))
    return true
  }

  private fun receiveAudio(
    data: DataMap,
    fileName: String,
    expectedSize: Long,
    expectedHash: String,
    recordedAt: Long,
  ): Boolean {
    val asset = data.getAsset(TransferProtocol.KEY_ASSET) ?: return false
    val response = Tasks.await(dataClient.getFdForAsset(asset)) ?: return false
    return response.inputStream.use { input ->
      recordingStorage.saveVerifiedAudio(input, fileName, expectedSize, expectedHash, recordedAt)
    }
  }

  private fun sanitizeFileName(name: String): String? =
    name.takeIf { it.matches(Regex("day-[0-9]{8}-[0-9]{6}\\.m4a")) }

  private fun saveError(error: Throwable) {
    prefs.edit().putString(PhoneContract.KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName).apply()
  }

  private fun discardLegacyReceipts() {
    val legacyKeys = prefs.all.keys.filter { it.startsWith(PhoneContract.LEGACY_RECEIVED_PREFIX) }
    if (legacyKeys.isEmpty()) return
    prefs.edit().also { editor -> legacyKeys.forEach(editor::remove) }.apply()
  }
}
