package com.example.dayrecorder.phone

internal enum class VerifiedArchiveResult {
  Existing,
  Stored,
  Unverified,
}

internal object VerifiedArchivePolicy {
  fun ensureVerified(
    isArchivedAndVerified: () -> Boolean,
    store: () -> Boolean,
  ): VerifiedArchiveResult {
    if (isArchivedAndVerified()) return VerifiedArchiveResult.Existing
    if (!store()) return VerifiedArchiveResult.Unverified
    return if (isArchivedAndVerified()) VerifiedArchiveResult.Stored else VerifiedArchiveResult.Unverified
  }
}
