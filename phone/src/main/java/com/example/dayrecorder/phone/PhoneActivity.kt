package com.example.dayrecorder.phone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.dayrecorder.phone.update.GitHubPhoneUpdateClient
import com.example.dayrecorder.phone.update.PhoneInstallRequestResult
import com.example.dayrecorder.phone.update.PhoneUpdateCheckResult
import com.example.dayrecorder.phone.update.PhoneUpdateRelease
import kotlin.concurrent.thread

class PhoneActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var updateStatus: TextView
  private lateinit var checkUpdate: Button
  private lateinit var installUpdate: Button
  private lateinit var updateClient: GitHubPhoneUpdateClient
  private var availableUpdate: PhoneUpdateRelease? = null
  private val handler = Handler(Looper.getMainLooper())
  private val refresh = object : Runnable {
    override fun run() {
      updateStatus()
      handler.postDelayed(this, 1_000L)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val density = resources.displayMetrics.density
    val padding = (24 * density).toInt()
    updateClient = GitHubPhoneUpdateClient(this)
    status = TextView(this).apply { textSize = 17f }
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

  private fun updateStatus() {
    val prefs = getSharedPreferences(PhoneContract.PREFS, MODE_PRIVATE)
    val count = prefs.getInt(PhoneContract.KEY_RECEIVED_COUNT, 0)
    val last = prefs.getString(PhoneContract.KEY_LAST_FILE, "").orEmpty()
    val error = prefs.getString(PhoneContract.KEY_LAST_ERROR, "").orEmpty()
    status.text = buildString {
      append("Received from watch: $count files\n")
      if (last.isNotBlank()) append("Last file: $last\n")
      append("\nSaved in My Files:\nInternal storage/Music/Day Recorder\n")
      append("\nTransfers are checksum-verified before the watch copy is removed.")
      if (error.isNotBlank()) append("\n\nTransfer error: $error")
    }
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
}
