package com.example.dayrecorder.recording

data class RetentionFile(
  val name: String,
  val sizeBytes: Long,
  val lastModifiedAt: Long,
)

data class RetentionPlan(
  val deleteNames: Set<String>,
  val warningCount: Int,
  val retainedBytes: Long,
  val oldestRetainedAt: Long?,
)

class RecordingRetentionPolicy(
  private val warningAgeMs: Long = WARNING_AGE_MS,
  private val hardAgeMs: Long = HARD_AGE_MS,
  private val stalePartialAgeMs: Long = STALE_PARTIAL_AGE_MS,
  private val maxBytes: Long = MAX_BYTES,
) {
  fun evaluate(
    files: List<RetentionFile>,
    now: Long,
    excludedFileName: String? = null,
  ): RetentionPlan {
    val recordings = files.filter { it.name.endsWith(".m4a") || it.name.endsWith(".m4a.partial") }
    val deleteNames = linkedSetOf<String>()

    recordings
      .filter { it.name != excludedFileName }
      .filter { it.name.endsWith(".partial") && ageOf(it, now) >= stalePartialAgeMs }
      .forEach { deleteNames += it.name }

    val finalized = recordings.filter { it.name.endsWith(".m4a") && it.name != excludedFileName }
    finalized
      .filter { ageOf(it, now) >= hardAgeMs }
      .forEach { deleteNames += it.name }

    var retainedBytes = recordings.filterNot { it.name in deleteNames }.sumOf { it.sizeBytes }
    finalized
      .filterNot { it.name in deleteNames }
      .sortedBy { it.lastModifiedAt }
      .forEach { file ->
        if (retainedBytes > maxBytes) {
          deleteNames += file.name
          retainedBytes -= file.sizeBytes
        }
      }

    val retainedFinalized = finalized.filterNot { it.name in deleteNames }
    return RetentionPlan(
      deleteNames = deleteNames,
      warningCount = retainedFinalized.count { ageOf(it, now) >= warningAgeMs },
      retainedBytes = recordings.filterNot { it.name in deleteNames }.sumOf { it.sizeBytes },
      oldestRetainedAt = retainedFinalized.minOfOrNull { it.lastModifiedAt },
    )
  }

  private fun ageOf(file: RetentionFile, now: Long): Long =
    (now - file.lastModifiedAt).coerceAtLeast(0L)

  companion object {
    const val WARNING_DAYS = 3
    const val HARD_LIMIT_DAYS = 7
    const val MAX_BYTES = 1_024L * 1_024L * 1_024L
    const val WARNING_AGE_MS = WARNING_DAYS * 24L * 60L * 60L * 1_000L
    const val HARD_AGE_MS = HARD_LIMIT_DAYS * 24L * 60L * 60L * 1_000L
    const val STALE_PARTIAL_AGE_MS = 24L * 60L * 60L * 1_000L
  }
}
