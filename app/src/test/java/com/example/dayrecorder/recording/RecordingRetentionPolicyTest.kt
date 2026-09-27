package com.example.dayrecorder.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRetentionPolicyTest {
  private val day = 24L * 60L * 60L * 1_000L
  private val now = 10L * day

  @Test
  fun `warns after three days and deletes after seven`() {
    val plan = RecordingRetentionPolicy().evaluate(
      listOf(
        RetentionFile("day-new.m4a", 10, now - 2 * day),
        RetentionFile("day-warning.m4a", 10, now - 4 * day),
        RetentionFile("day-expired.m4a", 10, now - 8 * day),
      ),
      now,
    )

    assertEquals(setOf("day-expired.m4a"), plan.deleteNames)
    assertEquals(1, plan.warningCount)
    assertEquals(20, plan.retainedBytes)
    assertEquals(now - 4 * day, plan.oldestRetainedAt)
  }

  @Test
  fun `deletes stale partial but never excluded active file`() {
    val plan = RecordingRetentionPolicy().evaluate(
      listOf(
        RetentionFile("stale.m4a.partial", 10, now - 2 * day),
        RetentionFile("active.m4a.partial", 10, now - 2 * day),
      ),
      now,
      excludedFileName = "active.m4a.partial",
    )

    assertTrue("stale.m4a.partial" in plan.deleteNames)
    assertFalse("active.m4a.partial" in plan.deleteNames)
  }

  @Test
  fun `storage ceiling removes oldest finalized files first`() {
    val policy = RecordingRetentionPolicy(maxBytes = 100)
    val plan = policy.evaluate(
      listOf(
        RetentionFile("old.m4a", 60, now - day),
        RetentionFile("new.m4a", 60, now),
      ),
      now,
    )

    assertEquals(setOf("old.m4a"), plan.deleteNames)
    assertEquals(60, plan.retainedBytes)
  }
}
