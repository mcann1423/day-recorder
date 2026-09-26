package com.example.dayrecorder.transfer

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.dayrecorder.recording.RecorderContract
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WatchTransferQueue(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(RecorderContract.PREFS, Context.MODE_PRIVATE)
  private val executor = Executors.newSingleThreadExecutor()
  private val running = AtomicBoolean(false)
  private val rerunRequested = AtomicBoolean(false)

  fun queueCompletedFiles(directory: File?, excludedFileName: String? = null) {
    if (directory == null) return
    if (!running.compareAndSet(false, true)) {
      rerunRequested.set(true)
      return
    }
    executor.execute {
      var excluded = excludedFileName
      do {
        rerunRequested.set(false)
        try {
          val queued = queuedNames()
          directory.listFiles { file -> file.isFile && file.extension.equals("m4a", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.filterNot { it.name == excluded || queued.contains(it.name) }
            ?.forEach(::queueFile)
          prefs.edit().remove(RecorderContract.KEY_TRANSFER_ERROR).apply()
        } catch (error: Throwable) {
          prefs.edit()
            .putString(RecorderContract.KEY_TRANSFER_ERROR, error.message ?: error.javaClass.simpleName)
            .apply()
        }
        // A request made while this pass was active is normally an End Day request;
        // by then the formerly active chunk has been finalized and is safe to queue.
        excluded = null
      } while (rerunRequested.get())
      running.set(false)
      // Close the narrow race between the final loop check and clearing running.
      if (rerunRequested.getAndSet(false)) queueCompletedFiles(directory)
    }
  }

  private fun queueFile(file: File) {
    val id = TransferProtocol.idFor(file.name)
    val checksum = FileHashes.sha256(file)
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      val request = PutDataMapRequest.create(TransferProtocol.AUDIO_PREFIX + id)
      request.dataMap.apply {
        putString(TransferProtocol.KEY_FILE_NAME, file.name)
        putLong(TransferProtocol.KEY_SIZE, file.length())
        putString(TransferProtocol.KEY_SHA256, checksum)
        putLong(TransferProtocol.KEY_RECORDED_AT, file.lastModified())
        putAsset(TransferProtocol.KEY_ASSET, Asset.createFromFd(descriptor))
      }
      // Deliberately non-urgent: Google Play services can batch delivery to save battery.
      Tasks.await(Wearable.getDataClient(appContext).putDataItem(request.asPutDataRequest()))
    }
    val queued = queuedNames().toMutableSet().apply { add(file.name) }
    prefs.edit()
      .putStringSet(KEY_QUEUED_NAMES, queued)
      .putInt(RecorderContract.KEY_QUEUED_COUNT, queued.size)
      .putString(RecorderContract.KEY_LAST_TRANSFER, file.name)
      .apply()
  }

  private fun queuedNames(): Set<String> = prefs.getStringSet(KEY_QUEUED_NAMES, emptySet())?.toSet().orEmpty()

  companion object {
    const val KEY_QUEUED_NAMES = "queued_file_names"
  }
}
