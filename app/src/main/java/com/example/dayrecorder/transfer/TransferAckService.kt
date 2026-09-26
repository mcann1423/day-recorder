package com.example.dayrecorder.transfer

import android.os.Environment
import com.example.dayrecorder.recording.RecorderContract
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class TransferAckService : WearableListenerService() {
  override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
    events.forEach { event ->
      if (event.type != DataEvent.TYPE_CHANGED) return@forEach
      val item = event.dataItem
      if (!item.uri.path.orEmpty().startsWith(TransferProtocol.ACK_PREFIX)) return@forEach
      val data = DataMapItem.fromDataItem(item).dataMap
      val name = data.getString(TransferProtocol.KEY_FILE_NAME) ?: return@forEach
      val expectedHash = data.getString(TransferProtocol.KEY_SHA256) ?: return@forEach
      acknowledge(name, expectedHash, item.uri.path.orEmpty())
    }
  }

  private fun acknowledge(name: String, expectedHash: String, ackPath: String) {
    if (!name.matches(Regex("day-[0-9]{8}-[0-9]{6}\\.m4a"))) return
    val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DayRecorder")
    val file = File(directory, name)
    if (file.exists()) {
      val actualHash = runCatching { FileHashes.sha256(file) }.getOrNull()
      if (actualHash != expectedHash || !file.delete()) {
        getSharedPreferences(RecorderContract.PREFS, MODE_PRIVATE).edit()
          .putString(RecorderContract.KEY_TRANSFER_ERROR, "Could not verify and remove $name")
          .apply()
        return
      }
    }

    val prefs = getSharedPreferences(RecorderContract.PREFS, MODE_PRIVATE)
    val queued = prefs.getStringSet(WatchTransferQueue.KEY_QUEUED_NAMES, emptySet()).orEmpty().toMutableSet()
    if (queued.remove(name)) {
      prefs.edit()
        .putStringSet(WatchTransferQueue.KEY_QUEUED_NAMES, queued)
        .putInt(RecorderContract.KEY_QUEUED_COUNT, queued.size)
        .putInt(RecorderContract.KEY_TRANSFERRED_COUNT, prefs.getInt(RecorderContract.KEY_TRANSFERRED_COUNT, 0) + 1)
        .apply()
    }

    Wearable.getDataClient(this).apply {
      deleteDataItems(android.net.Uri.parse("wear://*${TransferProtocol.AUDIO_PREFIX}${TransferProtocol.idFor(name)}"))
      deleteDataItems(android.net.Uri.parse("wear://*$ackPath"))
    }
  }
}
