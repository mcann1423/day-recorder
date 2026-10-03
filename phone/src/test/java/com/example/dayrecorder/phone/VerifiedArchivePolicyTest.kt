package com.example.dayrecorder.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedArchivePolicyTest {
  @Test
  fun existingVerifiedArchiveSkipsStorage() {
    var storeCalled = false

    val result = VerifiedArchivePolicy.ensureVerified(
      isArchivedAndVerified = { true },
      store = {
        storeCalled = true
        true
      },
    )

    assertEquals(VerifiedArchiveResult.Existing, result)
    assertFalse(storeCalled)
  }

  @Test
  fun newlyStoredArchiveMustPassFinalVerification() {
    var stored = false

    val result = VerifiedArchivePolicy.ensureVerified(
      isArchivedAndVerified = { stored },
      store = {
        stored = true
        true
      },
    )

    assertEquals(VerifiedArchiveResult.Stored, result)
    assertTrue(stored)
  }

  @Test
  fun successfulWriteWithoutVerifiedArchiveCannotBeAcknowledged() {
    val result = VerifiedArchivePolicy.ensureVerified(
      isArchivedAndVerified = { false },
      store = { true },
    )

    assertEquals(VerifiedArchiveResult.Unverified, result)
  }

  @Test
  fun failedWriteCannotBeAcknowledged() {
    val result = VerifiedArchivePolicy.ensureVerified(
      isArchivedAndVerified = { false },
      store = { false },
    )

    assertEquals(VerifiedArchiveResult.Unverified, result)
  }
}
