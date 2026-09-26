package com.example.dayrecorder.recording

import java.util.Calendar
import java.util.TimeZone

class DailyTransferPolicy(
  private val transferHour: Int = RecorderContract.DAILY_TRANSFER_HOUR,
) {
  fun delayUntilTransfer(nowEpochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowEpochMs }
    val target = Calendar.getInstance(timeZone).apply {
      timeInMillis = nowEpochMs
      set(Calendar.HOUR_OF_DAY, transferHour)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    return if (now.timeInMillis <= target.timeInMillis) {
      target.timeInMillis - now.timeInMillis
    } else {
      Long.MAX_VALUE
    }
  }
}
