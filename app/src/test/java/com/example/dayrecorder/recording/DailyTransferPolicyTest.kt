package com.example.dayrecorder.recording

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTransferPolicyTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val policy = DailyTransferPolicy(transferHour = 15)

  @Test
  fun schedulesThreePmWhenSessionStartsBeforeWindow() {
    assertEquals(6 * 60 * 60 * 1_000L, policy.delayUntilTransfer(at(9, 0), utc))
  }

  @Test
  fun firesImmediatelyAtThreePm() {
    assertEquals(0L, policy.delayUntilTransfer(at(15, 0), utc))
  }

  @Test
  fun skipsDailyTransferWhenSessionStartsAfterThreePm() {
    assertEquals(Long.MAX_VALUE, policy.delayUntilTransfer(at(15, 1), utc))
  }

  private fun at(hour: Int, minute: Int): Long =
    Calendar.getInstance(utc).apply {
      clear()
      set(2026, Calendar.SEPTEMBER, 18, hour, minute, 0)
    }.timeInMillis
}
