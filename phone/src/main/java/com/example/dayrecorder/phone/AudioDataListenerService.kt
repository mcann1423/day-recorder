package com.example.dayrecorder.phone

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.WearableListenerService

class AudioDataListenerService : WearableListenerService() {
  override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
    val hasAudio = events.any { event ->
      event.type == DataEvent.TYPE_CHANGED &&
        event.dataItem.uri.path.orEmpty().startsWith(TransferProtocol.AUDIO_PREFIX)
    }
    if (!hasAudio) return

    WorkManager.getInstance(this).enqueueUniqueWork(
      PhoneContract.UNIQUE_RECEIVE_WORK,
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      OneTimeWorkRequestBuilder<AudioReceiveWorker>().build(),
    )
  }
}
