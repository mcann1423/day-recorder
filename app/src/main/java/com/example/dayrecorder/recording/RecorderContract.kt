package com.example.dayrecorder.recording

object RecorderContract {
  const val ACTION_START = "com.example.dayrecorder.action.START"
  const val ACTION_PAUSE = "com.example.dayrecorder.action.PAUSE"
  const val ACTION_RESUME = "com.example.dayrecorder.action.RESUME"
  const val ACTION_STOP = "com.example.dayrecorder.action.STOP"

  const val PREFS = "recorder_state"
  const val KEY_STATE = "state"
  const val KEY_SESSION_STARTED_AT = "session_started_at"
  const val KEY_CHUNK_COUNT = "chunk_count"
  const val KEY_LAST_CHUNK = "last_chunk"
  const val KEY_LAST_ERROR = "last_error"
  const val KEY_STOP_REASON = "stop_reason"
  const val KEY_TRANSFERRED_COUNT = "transferred_count"
  const val KEY_QUEUED_COUNT = "queued_count"
  const val KEY_LAST_TRANSFER = "last_transfer"
  const val KEY_TRANSFER_ERROR = "transfer_error"

  const val STATE_IDLE = "idle"
  const val STATE_RECORDING = "recording"
  const val STATE_PAUSED = "paused"
  const val STATE_ERROR = "error"

  const val STOP_REASON_USER = "Ended by user"
  const val STOP_REASON_LOW_BATTERY = "Stopped at 20% battery"
  const val STOP_REASON_TIME_LIMIT = "Completed 10-hour session"

  const val SAMPLE_RATE = 16_000
  const val AAC_BIT_RATE = 24_000
  const val CHUNK_DURATION_MS = 15 * 60 * 1_000L
  const val SESSION_LIMIT_MS = 10 * 60 * 60 * 1_000L
  const val BATTERY_CHECK_INTERVAL_MS = 2 * 60 * 1_000L
  const val LOW_BATTERY_PERCENT = 20
  const val DAILY_TRANSFER_HOUR = 15
}
