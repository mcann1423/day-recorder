package com.example.dayrecorder.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.dayrecorder.R
import com.example.dayrecorder.recording.RecorderContract

class RecorderComplicationDataSourceService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
    complicationData(request.complicationType, isRecording())

  override fun getPreviewData(type: ComplicationType): ComplicationData =
    complicationData(type, recording = false)

  private fun isRecording(): Boolean {
    val state = getSharedPreferences(RecorderContract.PREFS, MODE_PRIVATE)
      .getString(RecorderContract.KEY_STATE, RecorderContract.STATE_IDLE)
    return state == RecorderContract.STATE_RECORDING || state == RecorderContract.STATE_PAUSED
  }

  private fun complicationData(type: ComplicationType, recording: Boolean): ComplicationData {
    val label = if (recording) "REC" else "Record"
    val description = if (recording) "Day Recorder is active" else "Start Day Recorder"
    val icon = MonochromaticImage.Builder(
      Icon.createWithResource(this, R.drawable.ic_complication_record),
    ).build()
    val tapAction = PendingIntent.getActivity(
      this,
      0,
      Intent(this, StartRecordingActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val contentDescription = PlainComplicationText.Builder(description).build()

    return if (type == ComplicationType.MONOCHROMATIC_IMAGE) {
      MonochromaticImageComplicationData.Builder(icon, contentDescription)
        .setTapAction(tapAction)
        .build()
    } else {
      ShortTextComplicationData.Builder(
        PlainComplicationText.Builder(label).build(),
        contentDescription,
      )
        .setMonochromaticImage(icon)
        .setTapAction(tapAction)
        .build()
    }
  }
}

object RecorderComplicationUpdates {
  fun request(context: Context) {
    ComplicationDataSourceUpdateRequester.create(
      context,
      ComponentName(context, RecorderComplicationDataSourceService::class.java),
    ).requestUpdateAll()
  }
}
