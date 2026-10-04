package com.example.dayrecorder.update

import com.example.dayrecorder.updatecore.UpdateNetworkException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay

internal object UpdateRetryPolicy {
  private val delaysMillis = longArrayOf(
    5_000L,
    15_000L,
    30_000L,
    60_000L,
    2 * 60_000L,
    5 * 60_000L,
  )

  fun delayMillis(consecutiveFailures: Int): Long =
    delaysMillis[(consecutiveFailures - 1).coerceIn(0, delaysMillis.lastIndex)]

  suspend fun <T> runUntilSuccess(
    operation: suspend () -> T,
    onRetry: (Long) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
  ): T {
    var consecutiveFailures = 0
    while (true) {
      try {
        return operation()
      } catch (error: UpdateNetworkException) {
        if (error.hasSslCause()) throw error
        consecutiveFailures += 1
        val retryDelay = delayMillis(consecutiveFailures)
        onRetry(retryDelay)
        wait(retryDelay)
      }
    }
  }

  fun message(delayMillis: Long): String {
    val delayText = if (delayMillis < 60_000L) {
      "${delayMillis / 1_000L} seconds"
    } else {
      val minutes = delayMillis / 60_000L
      "$minutes minute${if (minutes == 1L) "" else "s"}"
    }
    return "Connection interrupted. Retrying automatically in $delayText…"
  }

  private fun Throwable.hasSslCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is SSLException }
}
