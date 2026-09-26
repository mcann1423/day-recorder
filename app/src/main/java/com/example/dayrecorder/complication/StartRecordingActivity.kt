package com.example.dayrecorder.complication

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.dayrecorder.MainActivity
import com.example.dayrecorder.recording.RecorderController

/** User-visible trampoline that makes a complication tap a valid microphone-service start. */
class StartRecordingActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      RecorderController.start(this)
    } else {
      startActivity(Intent(this, MainActivity::class.java))
    }
    finish()
  }
}
