package com.example.dayrecorder.phone

data class PhoneRecordingInfo(
  val name: String,
  val size: Long,
  val recordedAt: Long,
)

data class PhoneRetentionStatus(
  val count: Int,
  val totalBytes: Long,
  val oldestRecordedAt: Long,
  val oldRecordingCount: Int,
  val ageWarning: Boolean,
  val storageWarning: Boolean,
)

class PhoneRetentionPolicy(
  private val warningAgeMs: Long = WARNING_AGE_MS,
  private val warningBytes: Long = WARNING_BYTES,
) {
  fun evaluate(recordings: Collection<PhoneRecordingInfo>, now: Long = System.currentTimeMillis()): PhoneRetentionStatus {
    val oldest = recordings.minOfOrNull(PhoneRecordingInfo::recordedAt) ?: 0L
    val oldCount = recordings.count { recording ->
      recording.recordedAt > 0L && now - recording.recordedAt >= warningAgeMs
    }
    val totalBytes = recordings.sumOf { it.size.coerceAtLeast(0L) }
    return PhoneRetentionStatus(
      count = recordings.size,
      totalBytes = totalBytes,
      oldestRecordedAt = oldest,
      oldRecordingCount = oldCount,
      ageWarning = oldCount > 0,
      storageWarning = totalBytes >= warningBytes,
    )
  }

  companion object {
    const val WARNING_DAYS = 180
    const val WARNING_GIB = 20
    const val WARNING_AGE_MS = WARNING_DAYS * 24L * 60L * 60L * 1_000L
    const val WARNING_BYTES = WARNING_GIB * 1_024L * 1_024L * 1_024L
  }
}
