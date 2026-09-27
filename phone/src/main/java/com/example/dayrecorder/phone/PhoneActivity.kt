package com.example.dayrecorder.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.example.dayrecorder.phone.update.GitHubPhoneUpdateClient
import com.example.dayrecorder.phone.update.PhoneInstallRequestResult
import com.example.dayrecorder.phone.update.PhoneUpdateCheckResult
import com.example.dayrecorder.phone.update.PhoneUpdateRelease
import kotlin.concurrent.thread
import java.util.ArrayDeque

class PhoneActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var updateStatus: TextView
  private lateinit var storageStatus: TextView
  private lateinit var chooseStorage: Button
  private lateinit var useDefaultStorage: Button
  private lateinit var purgeRecordings: Button
  private lateinit var checkUpdate: Button
  private lateinit var installUpdate: Button
  private lateinit var updateClient: GitHubPhoneUpdateClient
  private lateinit var recordingStorage: PhoneRecordingStorage
  private var availableUpdate: PhoneUpdateRelease? = null
  private var storageSnapshot: PhoneStorageSnapshot? = null
  private var inventoryBusy = false
  private var refreshCount = 0
  private val pendingMediaDeleteBatches = ArrayDeque<List<android.net.Uri>>()
  private val handler = Handler(Looper.getMainLooper())
  private val refresh = object : Runnable {
    override fun run() {
      updateStatus()
      if (refreshCount++ % 10 == 0) refreshInventory()
      handler.postDelayed(this, 1_000L)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val density = resources.displayMetrics.density
    val padding = (24 * density).toInt()
    updateClient = GitHubPhoneUpdateClient(this)
    recordingStorage = PhoneRecordingStorage(this)
    status = TextView(this).apply { textSize = 17f }
    storageStatus = TextView(this).apply {
      textSize = 17f
      gravity = Gravity.CENTER_HORIZONTAL
      text = "Scanning recordings…"
    }
    chooseStorage = Button(this).apply {
      text = "Choose recordings folder"
      setOnClickListener { chooseStorageLocation() }
    }
    useDefaultStorage = Button(this).apply {
      text = "Use default folder"
      setOnClickListener {
        recordingStorage.useDefaultLocation()
        refreshInventory()
        updateStatus()
      }
    }
    purgeRecordings = Button(this).apply {
      text = "Purge recordings"
      isEnabled = false
      setOnClickListener { confirmPurge() }
    }
    updateStatus = TextView(this).apply {
      textSize = 16f
      gravity = Gravity.CENTER_HORIZONTAL
    }
    checkUpdate = Button(this).apply {
      text = "Check for update"
      setOnClickListener { checkForUpdate() }
    }
    installUpdate = Button(this).apply {
      visibility = View.GONE
      setOnClickListener { availableUpdate?.let(::installUpdate) }
    }
    val content =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(padding, padding * 2, padding, padding)
        addView(TextView(this@PhoneActivity).apply {
          text = "Day Recorder"
          textSize = 28f
        })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
        addView(storageStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
        addView(chooseStorage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
        addView(useDefaultStorage, LinearLayout.LayoutParams(-1, -2))
        addView(purgeRecordings, LinearLayout.LayoutParams(-1, -2))
        addView(checkUpdate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
        addView(installUpdate, LinearLayout.LayoutParams(-1, -2))
        addView(updateStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
      }
    setContentView(ScrollView(this).apply { addView(content) })
  }

  override fun onStart() {
    super.onStart()
    handler.post(refresh)
  }

  override fun onStop() {
    handler.removeCallbacks(refresh)
    super.onStop()
  }

  @Deprecated("Deprecated in Android; retained for the classic Activity implementation")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_STORAGE_FOLDER) {
      if (resultCode != RESULT_OK) return
      val uri = data?.data ?: return
      runCatching { recordingStorage.selectLocation(uri) }
        .onSuccess {
          refreshInventory()
          updateStatus()
        }
        .onFailure { error ->
          storageStatus.text = error.message ?: "Could not use the selected folder"
        }
      return
    }
    if (requestCode == REQUEST_PURGE_MEDIA) {
      if (resultCode == RESULT_OK) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) launchNextMediaDeleteBatch()
        else purgeRemainingRecordings(legacy = true)
      } else {
        pendingMediaDeleteBatches.clear()
        setStorageControlsEnabled(true)
        storageStatus.text = "Purge canceled"
        refreshInventory()
      }
    }
  }

  private fun updateStatus() {
    val prefs = getSharedPreferences(PhoneContract.PREFS, MODE_PRIVATE)
    val count = prefs.getInt(PhoneContract.KEY_RECEIVED_COUNT, 0)
    val last = prefs.getString(PhoneContract.KEY_LAST_FILE, "").orEmpty()
    val error = prefs.getString(PhoneContract.KEY_LAST_ERROR, "").orEmpty()
    status.text = buildString {
      append("Received since this install: $count files\n")
      if (last.isNotBlank()) append("Last file: $last\n")
      append("\nTransfers are checksum-verified before the watch copy is removed.")
      if (error.isNotBlank()) append("\n\nTransfer error: $error")
    }
    useDefaultStorage.visibility = if (recordingStorage.isUsingCustomLocation()) View.VISIBLE else View.GONE
  }

  private fun chooseStorageLocation() {
    startActivityForResult(
      Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
          Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
      ),
      REQUEST_STORAGE_FOLDER,
    )
  }

  private fun refreshInventory() {
    if (inventoryBusy) return
    inventoryBusy = true
    thread(name = "phone-recording-inventory") {
      runCatching { recordingStorage.snapshot() }
        .onSuccess { snapshot ->
          runOnUiThread {
            storageSnapshot = snapshot
            renderStorage(snapshot)
            inventoryBusy = false
          }
        }
        .onFailure { error ->
          runOnUiThread {
            storageStatus.text = error.message ?: "Could not scan recordings"
            inventoryBusy = false
          }
        }
    }
  }

  private fun renderStorage(snapshot: PhoneStorageSnapshot) {
    val retention = snapshot.retention
    storageStatus.text = buildString {
      append("Stored on phone: ${retention.count} recordings (${formatBytes(retention.totalBytes)})\n")
      append("New recordings: ${recordingStorage.currentLocationLabel()}\n")
      if (retention.oldestRecordedAt > 0L) append("Oldest recording: ${formatAge(retention.oldestRecordedAt)}\n")
      append("\nPhone archive: no automatic deletion")
      append("\nWarnings: ${PhoneRetentionPolicy.WARNING_DAYS} days / ${PhoneRetentionPolicy.WARNING_GIB} GB")
      if (retention.ageWarning) {
        append("\n\nWarning: ${retention.oldRecordingCount} recording(s) are over ${PhoneRetentionPolicy.WARNING_DAYS} days old")
      }
      if (retention.storageWarning) append("\n\nWarning: recordings use at least ${PhoneRetentionPolicy.WARNING_GIB} GB")
      if (snapshot.inaccessibleLocations > 0) {
        append("\n\nWarning: ${snapshot.inaccessibleLocations} previously used folder(s) are unavailable")
      }
    }
    purgeRecordings.isEnabled = retention.count > 0
  }

  private fun confirmPurge() {
    val count = storageSnapshot?.retention?.count ?: return
    AlertDialog.Builder(this)
      .setTitle("Purge recordings?")
      .setMessage("Permanently delete all $count Day Recorder recordings from the default and previously selected folders? This cannot be undone.")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Delete") { _, _ -> beginPurge() }
      .show()
  }

  private fun beginPurge() {
    setStorageControlsEnabled(false)
    storageStatus.text = "Preparing purge…"
    thread(name = "phone-recording-purge") {
      runCatching { recordingStorage.defaultRecordingUris() }
        .onSuccess { defaultUris ->
          runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && defaultUris.isNotEmpty()) {
              pendingMediaDeleteBatches.clear()
              defaultUris.chunked(MAX_MEDIA_DELETE_BATCH).forEach(pendingMediaDeleteBatches::addLast)
              launchNextMediaDeleteBatch()
            } else {
              purgeRemainingRecordings(legacy = true)
            }
          }
        }
        .onFailure { error ->
          runOnUiThread {
            setStorageControlsEnabled(true)
            storageStatus.text = error.message ?: "Could not purge recordings"
            refreshInventory()
          }
        }
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun launchNextMediaDeleteBatch() {
    if (pendingMediaDeleteBatches.isEmpty()) {
      purgeRemainingRecordings(legacy = false)
      return
    }
    val request = MediaStore.createDeleteRequest(contentResolver, pendingMediaDeleteBatches.removeFirst())
    startIntentSenderForResult(request.intentSender, REQUEST_PURGE_MEDIA, null, 0, 0, 0)
  }

  private fun purgeRemainingRecordings(legacy: Boolean) {
    storageStatus.text = "Purging recordings…"
    thread(name = "phone-recording-purge-finish") {
      val result = runCatching {
        if (legacy) recordingStorage.purgeLegacyAll() else recordingStorage.purgeCustomRecordings()
      }
      runOnUiThread {
        setStorageControlsEnabled(true)
        storageStatus.text = result.fold(
          onSuccess = { "Purge complete" },
          onFailure = { it.message ?: "Could not purge every recording" },
        )
        inventoryBusy = false
        refreshInventory()
      }
    }
  }

  private fun setStorageControlsEnabled(enabled: Boolean) {
    chooseStorage.isEnabled = enabled
    useDefaultStorage.isEnabled = enabled
    purgeRecordings.isEnabled = enabled && (storageSnapshot?.retention?.count ?: 0) > 0
  }

  private fun checkForUpdate() {
    setUpdateBusy(true, "Checking GitHub…")
    val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
    thread(name = "phone-update-check") {
      runCatching { updateClient.check(currentVersionCode) }
        .onSuccess { result ->
          runOnUiThread {
            when (result) {
              is PhoneUpdateCheckResult.Available -> {
                availableUpdate = result.release
                installUpdate.text = "Install ${result.release.versionName}"
                installUpdate.visibility = View.VISIBLE
                setUpdateBusy(false, "Version ${result.release.versionName} is available")
              }
              is PhoneUpdateCheckResult.Current -> {
                availableUpdate = null
                installUpdate.visibility = View.GONE
                setUpdateBusy(false, "You have the latest version (${result.latestVersionName})")
              }
            }
          }
        }
        .onFailure { error -> runOnUiThread { setUpdateBusy(false, error.message ?: "Update check failed") } }
    }
  }

  private fun installUpdate(release: PhoneUpdateRelease) {
    setUpdateBusy(true, "Downloading and verifying…")
    thread(name = "phone-update-download") {
      runCatching { updateClient.downloadAndVerify(release) }
        .onSuccess { apk ->
          runOnUiThread {
            val message = when (updateClient.requestInstall(apk)) {
              PhoneInstallRequestResult.Launched -> "Confirm the update in Android Installer"
              PhoneInstallRequestResult.PermissionRequired -> "Allow this source, then tap Install again"
            }
            setUpdateBusy(false, message)
          }
        }
        .onFailure { error -> runOnUiThread { setUpdateBusy(false, error.message ?: "Update download failed") } }
    }
  }

  private fun setUpdateBusy(busy: Boolean, message: String) {
    checkUpdate.isEnabled = !busy
    installUpdate.isEnabled = !busy
    updateStatus.text = message
  }

  private fun formatBytes(bytes: Long): String =
    when {
      bytes >= 1_024L * 1_024L * 1_024L -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
      bytes >= 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
      bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
      else -> "$bytes B"
    }

  private fun formatAge(timestamp: Long): String {
    val days = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / (24L * 60L * 60L * 1_000L))
    return if (days == 0L) "today" else "$days day${if (days == 1L) "" else "s"} ago"
  }

  companion object {
    private const val REQUEST_STORAGE_FOLDER = 1001
    private const val REQUEST_PURGE_MEDIA = 1002
    private const val MAX_MEDIA_DELETE_BATCH = 2_000
  }
}
