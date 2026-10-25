package com.example.dayrecorder.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecurringTransferPolicyTest {
  private val policy = RecurringTransferPolicy(intervalMs = 60 * 60 * 1_000L)

  @Test
  fun schedulesFirstTransferOneHourAfterSessionStart() {
    assertEquals(4_600_000L, policy.nextTransferAt(1_000_000L))
  }

  @Test
  fun schedulesEachSubsequentTransferOneHourAfterThePriorDeadline() {
    val first = policy.nextTransferAt(1_000_000L)
    assertEquals(8_200_000L, policy.nextTransferAt(first))
  }
}
