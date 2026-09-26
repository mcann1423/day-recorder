package com.example.dayrecorder.phone

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioReceiveWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  private val prefs = applicationContext.getSharedPreferences(PhoneContract.PREFS, Context.MODE_PRIVATE)
  private val dataClient = Wearable.getDataClient(applicationContext)

  override fun doWork(): Result {
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

    if (!prefs.getBoolean(PhoneContract.RECEIVED_PREFIX + expectedHash, false)) {
      val asset = data.getAsset(TransferProtocol.KEY_ASSET) ?: return false
      val response = Tasks.await(dataClient.getFdForAsset(asset)) ?: return false
      val input = response.inputStream
      if (!saveVerifiedAudio(input, fileName, expectedSize, expectedHash, recordedAt)) return false
      prefs.edit()
        .putBoolean(PhoneContract.RECEIVED_PREFIX + expectedHash, true)
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

  private fun saveVerifiedAudio(
    input: InputStream,
    fileName: String,
    expectedSize: Long,
    expectedHash: String,
    recordedAt: Long,
  ): Boolean {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(recordedAt))
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
      put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
      put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Day Recorder/$day")
      put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val resolver = applicationContext.contentResolver
    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      var copied = 0L
      resolver.openOutputStream(uri, "w")!!.use { output ->
        DigestInputStream(input, digest).use { source ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count > 0) {
              output.write(buffer, 0, count)
              copied += count
            }
          }
        }
      }
      val actualHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
      if (copied != expectedSize || actualHash != expectedHash) {
        resolver.delete(uri, null, null)
        false
      } else {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        true
      }
    } catch (error: Throwable) {
      resolver.delete(uri, null, null)
      throw error
    }
  }

  private fun sanitizeFileName(name: String): String? =
    name.takeIf { it.matches(Regex("day-[0-9]{8}-[0-9]{6}\\.m4a")) }

  private fun saveError(error: Throwable) {
    prefs.edit().putString(PhoneContract.KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName).apply()
  }
}
