package com.example.dayrecorder.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRetentionPolicyTest {
  private val day = 24L * 60L * 60L * 1_000L
  private val now = 400L * day

  @Test
  fun `reports count bytes and oldest recording without deleting`() {
    val status = PhoneRetentionPolicy().evaluate(
      listOf(
        PhoneRecordingInfo("new.m4a", 20, now - day),
        PhoneRecordingInfo("old.m4a", 30, now - 200 * day),
      ),
      now,
    )

    assertEquals(2, status.count)
    assertEquals(50, status.totalBytes)
    assertEquals(now - 200 * day, status.oldestRecordedAt)
    assertEquals(1, status.oldRecordingCount)
    assertTrue(status.ageWarning)
  }

  @Test
  fun `warns at storage ceiling but never returns deletion instructions`() {
    val status = PhoneRetentionPolicy(warningBytes = 100).evaluate(
      listOf(PhoneRecordingInfo("large.m4a", 100, now)),
      now,
    )

    assertTrue(status.storageWarning)
    assertFalse(status.ageWarning)
  }
}
