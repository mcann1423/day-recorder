package com.example.dayrecorder.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhonePurgeOutcomeTest {
  @Test
  fun `reports complete deletion`() {
    val outcome = PhonePurgeOutcome(requestedCount = 3, remainingCount = 0)

    assertEquals(3, outcome.deletedCount)
    assertEquals("Deleted 3 of 3 recordings", outcome.displayText())
  }

  @Test
  fun `reports recordings that remain`() {
    val outcome = PhonePurgeOutcome(requestedCount = 5, remainingCount = 2)

    assertEquals(3, outcome.deletedCount)
    assertEquals(
      "Deleted 3 of 5 recordings\n2 recording(s) remain; check folder access and try again.",
      outcome.displayText(),
    )
  }
}
