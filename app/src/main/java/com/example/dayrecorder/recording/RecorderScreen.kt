package com.example.dayrecorder.recording

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun RecorderScreen(
  hasMicrophonePermission: Boolean,
  onStart: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onStop: () -> Unit,
  isPowerSaveMode: () -> Boolean,
) {
  val context = LocalContext.current
  var state by remember { mutableStateOf(RecorderContract.STATE_IDLE) }
  var chunkCount by remember { mutableIntStateOf(0) }
  var lastChunk by remember { mutableStateOf("") }
  var lastError by remember { mutableStateOf("") }
  var stopReason by remember { mutableStateOf("") }
  var queuedCount by remember { mutableIntStateOf(0) }
  var transferredCount by remember { mutableIntStateOf(0) }
  var transferError by remember { mutableStateOf("") }
  var showSettings by remember { mutableStateOf(false) }
  var powerSaveMode by remember { mutableStateOf(isPowerSaveMode()) }
  val packageInfo = remember(context) {
    context.packageManager.getPackageInfo(context.packageName, 0)
  }
  val versionName = packageInfo.versionName ?: "Unknown"
  val buildNumber = packageInfo.longVersionCode.toString()

  LaunchedEffect(Unit) {
    while (true) {
      val prefs = context.getSharedPreferences(RecorderContract.PREFS, Context.MODE_PRIVATE)
      state = prefs.getString(RecorderContract.KEY_STATE, RecorderContract.STATE_IDLE) ?: RecorderContract.STATE_IDLE
      chunkCount = prefs.getInt(RecorderContract.KEY_CHUNK_COUNT, 0)
      lastChunk = prefs.getString(RecorderContract.KEY_LAST_CHUNK, "") ?: ""
      lastError = prefs.getString(RecorderContract.KEY_LAST_ERROR, "") ?: ""
      stopReason = prefs.getString(RecorderContract.KEY_STOP_REASON, "") ?: ""
      queuedCount = prefs.getInt(RecorderContract.KEY_QUEUED_COUNT, 0)
      transferredCount = prefs.getInt(RecorderContract.KEY_TRANSFERRED_COUNT, 0)
      transferError = prefs.getString(RecorderContract.KEY_TRANSFER_ERROR, "") ?: ""
      powerSaveMode = isPowerSaveMode()
      delay(1_000)
    }
  }

  BackHandler(enabled = showSettings) { showSettings = false }

  if (showSettings) {
    RecorderSettingsScreen(
      state = state,
      chunkCount = chunkCount,
      lastChunk = lastChunk,
      lastError = lastError,
      stopReason = stopReason,
      queuedCount = queuedCount,
      transferredCount = transferredCount,
      transferError = transferError,
      powerSaveMode = powerSaveMode,
      versionName = versionName,
      buildNumber = buildNumber,
      onBack = { showSettings = false },
    )
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Day Recorder", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))

    when (state) {
      RecorderContract.STATE_RECORDING -> {
        Button(onClick = onPause) { Text("Pause") }
        OutlinedButton(onClick = onStop) { Text("Stop") }
        OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
      }
      RecorderContract.STATE_PAUSED -> {
        Button(onClick = onResume) { Text("Resume") }
        OutlinedButton(onClick = onStop) { Text("Stop") }
        OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
      }
      else -> {
        Button(onClick = onStart) { Text(if (hasMicrophonePermission) "Start" else "Allow mic & start") }
        OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
      }
    }
  }
}

@Composable
private fun RecorderSettingsScreen(
  state: String,
  chunkCount: Int,
  lastChunk: String,
  lastError: String,
  stopReason: String,
  queuedCount: Int,
  transferredCount: Int,
  transferError: String,
  powerSaveMode: Boolean,
  versionName: String,
  buildNumber: String,
  onBack: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 28.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    OutlinedButton(onClick = onBack) { Text("Back") }
    Spacer(Modifier.height(8.dp))
    Text("Settings", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    StatLine("Status", state.displayName())
    StatLine("App version", versionName)
    StatLine("Build number", buildNumber)
    StatLine("Chunks recorded", chunkCount.toString())
    StatLine("Power saving", if (powerSaveMode) "On" else "Off")
    StatLine("Transfers", "3:00 PM and session end")
    StatLine("Phone received", transferredCount.toString())
    StatLine("Queued", queuedCount.toString())
    if (lastChunk.isNotBlank()) StatLine("Last recording", lastChunk)
    if (stopReason.isNotBlank()) StatLine("Last stop", stopReason)
    if (lastError.isNotBlank()) ErrorLine("Recording", lastError)
    if (transferError.isNotBlank()) ErrorLine("Transfer", transferError)
    Spacer(Modifier.height(18.dp))
  }
}

@Composable
private fun StatLine(label: String, value: String) {
  Text(label, style = MaterialTheme.typography.labelMedium)
  Text(value, textAlign = TextAlign.Center)
  Spacer(Modifier.height(10.dp))
}

@Composable
private fun ErrorLine(label: String, value: String) {
  Text("$label error", style = MaterialTheme.typography.labelMedium)
  Text(value, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
  Spacer(Modifier.height(10.dp))
}

private fun String.displayName(): String =
  when (this) {
    RecorderContract.STATE_RECORDING -> "Recording"
    RecorderContract.STATE_PAUSED -> "Paused"
    RecorderContract.STATE_ERROR -> "Error"
    else -> "Not recording"
  }
