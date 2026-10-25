package com.example.dayrecorder.recording

/**
 * Schedules bounded in-session handoffs relative to the start of recording.
 *
 * Using elapsed realtime keeps the cadence stable if the wall clock changes
 * while a recording day is in progress.
 */
class RecurringTransferPolicy(
  private val intervalMs: Long = RecorderContract.TRANSFER_INTERVAL_MS,
) {
  init {
    require(intervalMs > 0L) { "Transfer interval must be positive" }
  }

  fun nextTransferAt(nowElapsedMs: Long): Long = nowElapsedMs + intervalMs
}
