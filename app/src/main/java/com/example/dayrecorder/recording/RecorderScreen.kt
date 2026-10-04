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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dayrecorder.update.GitHubUpdateClient
import com.example.dayrecorder.update.InstallRequestResult
import com.example.dayrecorder.update.UpdateCheckResult
import com.example.dayrecorder.update.UpdateDownloadPhase
import com.example.dayrecorder.update.UpdateDownloadService
import com.example.dayrecorder.update.UpdateDownloadStatus
import com.example.dayrecorder.update.UpdateDownloadStore
import com.example.dayrecorder.update.UpdateRelease
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
  var pendingBytes by remember { mutableStateOf(0L) }
  var oldestPendingAt by remember { mutableStateOf(0L) }
  var retentionWarningCount by remember { mutableIntStateOf(0) }
  var retentionPurgedCount by remember { mutableIntStateOf(0) }
  var lastRetentionAction by remember { mutableStateOf("") }
  var showSettings by remember { mutableStateOf(false) }
  var confirmPurge by remember { mutableStateOf(false) }
  var updateActionBusy by remember { mutableStateOf(false) }
  var updateMessage by remember { mutableStateOf("") }
  var availableUpdate by remember { mutableStateOf<UpdateRelease?>(null) }
  var updateDownloadStatus by remember { mutableStateOf(UpdateDownloadStatus()) }
  var powerSaveMode by remember { mutableStateOf(isPowerSaveMode()) }
  val scope = rememberCoroutineScope()
  val updateClient = remember(context) { GitHubUpdateClient(context) }
  val updateStore = remember(context) { UpdateDownloadStore(context) }
  val packageInfo = remember(context) {
    context.packageManager.getPackageInfo(context.packageName, 0)
  }
  val versionName = packageInfo.versionName ?: "Unknown"
  val buildNumber = packageInfo.longVersionCode.toString()
  val updateBusy = updateActionBusy || updateDownloadStatus.isBusy

  LaunchedEffect(Unit) {
    if (updateStore.clearIfInstalled(packageInfo.longVersionCode)) {
      UpdateDownloadService.cancelCompletedNotification(context)
    }
    withContext(Dispatchers.IO) { RecordingRetentionManager(context).maintain() }
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
      pendingBytes = prefs.getLong(RecorderContract.KEY_PENDING_BYTES, 0L)
      oldestPendingAt = prefs.getLong(RecorderContract.KEY_OLDEST_PENDING_AT, 0L)
      retentionWarningCount = prefs.getInt(RecorderContract.KEY_RETENTION_WARNING_COUNT, 0)
      retentionPurgedCount = prefs.getInt(RecorderContract.KEY_RETENTION_PURGED_COUNT, 0)
      lastRetentionAction = prefs.getString(RecorderContract.KEY_LAST_RETENTION_ACTION, "") ?: ""
      powerSaveMode = isPowerSaveMode()
      updateDownloadStatus = updateStore.snapshot()
      if (!updateActionBusy && updateDownloadStatus.phase != UpdateDownloadPhase.IDLE) {
        updateMessage = updateDownloadStatus.message
      }
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
      pendingBytes = pendingBytes,
      oldestPendingAt = oldestPendingAt,
      retentionWarningCount = retentionWarningCount,
      retentionPurgedCount = retentionPurgedCount,
      lastRetentionAction = lastRetentionAction,
      powerSaveMode = powerSaveMode,
      versionName = versionName,
      buildNumber = buildNumber,
      updateBusy = updateBusy,
      updateMessage = updateMessage,
      availableUpdate = availableUpdate,
      onBack = { showSettings = false },
      onPurge = { confirmPurge = true },
      onCheckUpdate = {
        updateActionBusy = true
        updateMessage = "Checking GitHub…"
        scope.launch {
          runCatching { withContext(Dispatchers.IO) { updateClient.check(packageInfo.longVersionCode) } }
            .onSuccess { result ->
              when (result) {
                is UpdateCheckResult.Available -> {
                  availableUpdate = result.release
                  val status = updateStore.snapshot()
                  updateDownloadStatus = status
                  updateMessage = if (status.isFor(result.release)) {
                    status.message
                  } else {
                    "Version ${result.release.versionName} is available"
                  }
                }
                is UpdateCheckResult.Current -> {
                  availableUpdate = null
                  updateMessage = "You have the latest version (${result.latestVersionName})"
                }
              }
            }
            .onFailure { error -> updateMessage = error.message ?: "Update check failed" }
          updateActionBusy = false
        }
      },
      onInstallUpdate = { release ->
        updateActionBusy = true
        updateMessage = "Preparing download…"
        scope.launch {
          val cachedApk = withContext(Dispatchers.IO) { updateClient.verifiedCachedApk(release) }
          if (cachedApk != null) {
            runCatching { updateClient.requestInstall(release, cachedApk) }
              .onSuccess { result ->
                updateMessage = when (result) {
                  InstallRequestResult.Launched -> {
                    UpdateDownloadService.cancelCompletedNotification(context)
                    "Confirm the update in Android Installer"
                  }
                  InstallRequestResult.PermissionRequired ->
                    "Allow this source, return here, then tap Install again. The verified download will be reused."
                }
              }
              .onFailure { error -> updateMessage = error.message ?: "Could not open Android Installer" }
          } else {
            runCatching { UpdateDownloadService.start(context, release) }
              .onSuccess {
                updateDownloadStatus = UpdateDownloadStatus(
                  phase = UpdateDownloadPhase.DOWNLOADING,
                  versionCode = release.versionCode,
                  apkName = release.apkName,
                  message = "Preparing download…",
                )
                updateMessage = "Preparing download…"
              }
              .onFailure { error -> updateMessage = error.message ?: "Could not start update download" }
            }
          updateActionBusy = false
        }
      },
    )
    if (confirmPurge) {
      AlertDialog(
        onDismissRequest = { confirmPurge = false },
        title = { Text("Purge recordings?") },
        text = { Text("Deletes all completed and stale recordings still stored on this watch.") },
        confirmButton = {
          TextButton(
            onClick = {
              confirmPurge = false
              scope.launch { withContext(Dispatchers.IO) { RecordingRetentionManager(context).purgeAll() } }
            },
          ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
      )
    }
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
  pendingBytes: Long,
  oldestPendingAt: Long,
  retentionWarningCount: Int,
  retentionPurgedCount: Int,
  lastRetentionAction: String,
  powerSaveMode: Boolean,
  versionName: String,
  buildNumber: String,
  updateBusy: Boolean,
  updateMessage: String,
  availableUpdate: UpdateRelease?,
  onBack: () -> Unit,
  onPurge: () -> Unit,
  onCheckUpdate: () -> Unit,
  onInstallUpdate: (UpdateRelease) -> Unit,
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
    StatLine("Watch retention", "7 days / 1 GB")
    StatLine("Pending storage", formatBytes(pendingBytes))
    if (oldestPendingAt > 0L) StatLine("Oldest pending", formatAge(oldestPendingAt))
    if (retentionWarningCount > 0) {
      ErrorLine("Retention", "$retentionWarningCount recording(s) are over 3 days old")
    }
    if (retentionPurgedCount > 0) StatLine("Files purged", retentionPurgedCount.toString())
    if (lastRetentionAction.isNotBlank()) StatLine("Last cleanup", lastRetentionAction)
    if (lastChunk.isNotBlank()) StatLine("Last recording", lastChunk)
    if (stopReason.isNotBlank()) StatLine("Last stop", stopReason)
    if (lastError.isNotBlank()) ErrorLine("Recording", lastError)
    if (transferError.isNotBlank()) ErrorLine("Transfer", transferError)
    Spacer(Modifier.height(18.dp))
    OutlinedButton(
      enabled = state == RecorderContract.STATE_IDLE && !updateBusy,
      onClick = onCheckUpdate,
    ) { Text(if (updateBusy) "Working…" else "Check for update") }
    availableUpdate?.let { release ->
      Spacer(Modifier.height(8.dp))
      Button(
        enabled = state == RecorderContract.STATE_IDLE && !updateBusy,
        onClick = { onInstallUpdate(release) },
      ) { Text("Install ${release.versionName}") }
      if (release.releaseNotes.isNotBlank()) StatLine("Release notes", release.releaseNotes)
    }
    if (updateMessage.isNotBlank()) StatLine("Updater", updateMessage)
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
      enabled = state == RecorderContract.STATE_IDLE && pendingBytes > 0L,
      onClick = onPurge,
    ) { Text("Purge recordings") }
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

private fun formatBytes(bytes: Long): String =
  when {
    bytes >= 1_024L * 1_024L * 1_024L -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
    bytes >= 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
  }

private fun formatAge(timestamp: Long): String {
  val ageMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
  val days = TimeUnit.MILLISECONDS.toDays(ageMs)
  if (days > 0) return "$days day${if (days == 1L) "" else "s"}"
  val hours = TimeUnit.MILLISECONDS.toHours(ageMs)
  return "$hours hour${if (hours == 1L) "" else "s"}"
}
