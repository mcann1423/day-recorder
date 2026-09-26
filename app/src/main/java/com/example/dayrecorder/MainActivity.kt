package com.example.dayrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.dayrecorder.recording.RecorderController
import com.example.dayrecorder.recording.RecorderScreen
import com.example.dayrecorder.theme.DayRecorderTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      var hasMicrophonePermission by remember {
        mutableStateOf(
          ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
        )
      }
      val powerManager = getSystemService(PowerManager::class.java)
      val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          hasMicrophonePermission = granted
          if (granted) RecorderController.start(this)
        }

      LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
          requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
      }

      DayRecorderTheme {
        RecorderScreen(
          hasMicrophonePermission = hasMicrophonePermission,
          onStart = {
            if (hasMicrophonePermission) RecorderController.start(this)
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          },
          onPause = { RecorderController.pause(this) },
          onResume = { RecorderController.resume(this) },
          onStop = { RecorderController.stop(this) },
          isPowerSaveMode = { powerManager.isPowerSaveMode },
        )
      }
    }
  }
}
