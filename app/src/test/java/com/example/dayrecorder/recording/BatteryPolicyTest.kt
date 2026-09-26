package com.example.dayrecorder.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {
  private val policy = BatteryPolicy(stopAtPercent = 20)

  @Test
  fun stopsAtThreshold() {
    assertTrue(policy.shouldStop(20))
  }

  @Test
  fun stopsBelowThreshold() {
    assertTrue(policy.shouldStop(19))
  }

  @Test
  fun continuesAboveThreshold() {
    assertFalse(policy.shouldStop(21))
  }

  @Test
  fun ignoresUnavailableCapacity() {
    assertFalse(policy.shouldStop(Int.MIN_VALUE))
  }
}
