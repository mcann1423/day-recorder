package com.example.dayrecorder.recording

class ChunkPolicy(
  private val durationMs: Long = RecorderContract.CHUNK_DURATION_MS,
) {
  fun shouldRotate(elapsedMs: Long): Boolean = elapsedMs >= durationMs
}
