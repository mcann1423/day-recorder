package com.example.dayrecorder.phone

data class PhonePurgeOutcome(
  val requestedCount: Int,
  val remainingCount: Int,
) {
  val deletedCount: Int = (requestedCount - remainingCount).coerceAtLeast(0)

  fun displayText(): String = buildString {
    append("Deleted $deletedCount of $requestedCount recordings")
    if (remainingCount > 0) append("\n$remainingCount recording(s) remain; check folder access and try again.")
  }
}
