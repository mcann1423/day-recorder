package com.example.dayrecorder.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object RecorderController {
  fun start(context: Context) = send(context, RecorderContract.ACTION_START, foreground = true)
  fun pause(context: Context) = send(context, RecorderContract.ACTION_PAUSE)
  fun resume(context: Context) = send(context, RecorderContract.ACTION_RESUME)
  fun stop(context: Context) = send(context, RecorderContract.ACTION_STOP)

  private fun send(context: Context, action: String, foreground: Boolean = false) {
    val intent = Intent(context, RecorderService::class.java).setAction(action)
    if (foreground) ContextCompat.startForegroundService(context, intent)
    else context.startService(intent)
  }
}
