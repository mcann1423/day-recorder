package com.example.dayrecorder.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPolicyTest {
  private val policy = ChunkPolicy(durationMs = 900_000)

  @Test
  fun doesNotRotateBeforeFixedBoundary() {
    assertFalse(policy.shouldRotate(elapsedMs = 899_999))
  }

  @Test
  fun rotatesAtFixedBoundary() {
    assertTrue(policy.shouldRotate(elapsedMs = 900_000))
  }
}
