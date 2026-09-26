package com.example.dayrecorder.phone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class PhoneActivity : Activity() {
  private lateinit var status: TextView
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
    status = TextView(this).apply { textSize = 17f }
    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(padding, padding * 2, padding, padding)
        addView(TextView(this@PhoneActivity).apply {
          text = "Day Recorder"
          textSize = 28f
        })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
      },
    )
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
}
