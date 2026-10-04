package com.example.dayrecorder.update

import com.example.dayrecorder.updatecore.UpdateNetworkException
import java.net.SocketException
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateRetryPolicyTest {
  @Test
  fun `backs off quickly before capping at five minutes`() {
    assertEquals(5_000L, UpdateRetryPolicy.delayMillis(1))
    assertEquals(15_000L, UpdateRetryPolicy.delayMillis(2))
    assertEquals(30_000L, UpdateRetryPolicy.delayMillis(3))
    assertEquals(60_000L, UpdateRetryPolicy.delayMillis(4))
    assertEquals(120_000L, UpdateRetryPolicy.delayMillis(5))
    assertEquals(300_000L, UpdateRetryPolicy.delayMillis(6))
    assertEquals(300_000L, UpdateRetryPolicy.delayMillis(100))
  }

  @Test
  fun `formats automatic retry messages`() {
    assertEquals(
      "Connection interrupted. Retrying automatically in 5 seconds…",
      UpdateRetryPolicy.message(5_000L),
    )
    assertEquals(
      "Connection interrupted. Retrying automatically in 1 minute…",
      UpdateRetryPolicy.message(60_000L),
    )
    assertEquals(
      "Connection interrupted. Retrying automatically in 5 minutes…",
      UpdateRetryPolicy.message(300_000L),
    )
  }

  @Test
  fun `keeps retrying transient failures until the operation succeeds`() = runBlocking {
    var attempts = 0
    val waits = mutableListOf<Long>()

    val result = UpdateRetryPolicy.runUntilSuccess(
      operation = {
        attempts += 1
        if (attempts <= 8) throw UpdateNetworkException("connection aborted", SocketException())
        "complete"
      },
      onRetry = {},
      wait = { waits += it },
    )

    assertEquals("complete", result)
    assertEquals(9, attempts)
    assertEquals(
      listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L),
      waits,
    )
  }

  @Test
  fun `does not retry an SSL failure`() {
    val failure = UpdateNetworkException("TLS failed", SSLException("certificate rejected"))
    var retryCount = 0

    val thrown = assertThrows(UpdateNetworkException::class.java) {
      runBlocking {
        UpdateRetryPolicy.runUntilSuccess(
          operation = { throw failure },
          onRetry = { retryCount += 1 },
          wait = {},
        )
      }
    }

    assertSame(failure, thrown)
    assertEquals(0, retryCount)
  }
}
