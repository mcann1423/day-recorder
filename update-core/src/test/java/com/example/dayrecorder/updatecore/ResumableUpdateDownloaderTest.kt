package com.example.dayrecorder.updatecore

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableUpdateDownloaderTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `resumes a partial file after a connection abort`() {
    val payload = "abcdefghij".toByteArray(StandardCharsets.UTF_8)
    val first = FakeConnection(payload, failAfterBytes = 5)
    val second = FakeConnection(
      payload.copyOfRange(5, payload.size),
      responseCodeValue = HttpURLConnection.HTTP_PARTIAL,
      contentRange = "bytes 5-9/10",
    )
    val connections = ArrayDeque(listOf(first, second))
    val destination = temporaryFolder.newFile("update.apk.part")
    destination.writeBytes(byteArrayOf())

    downloader(connections).download(
      url = TEST_URL,
      destination = destination,
      maxBytes = 100,
      expectedBytes = payload.size.toLong(),
    )

    assertArrayEquals(payload, destination.readBytes())
    assertEquals("bytes=5-", second.requestHeaders["Range"])
  }

  @Test
  fun `restarts safely when a server ignores the range request`() {
    val payload = "abcdefghij".toByteArray(StandardCharsets.UTF_8)
    val connection = FakeConnection(payload)
    val destination = temporaryFolder.newFile("update.apk.part")
    destination.writeText("abc")

    downloader(ArrayDeque(listOf(connection))).download(
      url = TEST_URL,
      destination = destination,
      maxBytes = 100,
      expectedBytes = payload.size.toLong(),
    )

    assertArrayEquals(payload, destination.readBytes())
    assertEquals("bytes=3-", connection.requestHeaders["Range"])
  }

  @Test
  fun `resumes a partial file left by an earlier process`() {
    val destination = temporaryFolder.newFile("update.apk.part")
    destination.writeText("abcde")
    val connection = FakeConnection(
      "fghij".toByteArray(StandardCharsets.UTF_8),
      responseCodeValue = HttpURLConnection.HTTP_PARTIAL,
      contentRange = "bytes 5-9/10",
    )

    downloader(ArrayDeque(listOf(connection))).download(TEST_URL, destination, 100, 10)

    assertEquals("abcdefghij", destination.readText())
    assertEquals("bytes=5-", connection.requestHeaders["Range"])
  }

  @Test
  fun `preserves the range header across an HTTPS redirect`() {
    val destination = temporaryFolder.newFile("update.apk.part")
    destination.writeText("abcde")
    val redirect = FakeConnection(
      byteArrayOf(),
      responseCodeValue = HttpURLConnection.HTTP_MOVED_TEMP,
      location = "https://assets.example.test/update.apk",
    )
    val asset = FakeConnection(
      "fghij".toByteArray(StandardCharsets.UTF_8),
      responseCodeValue = HttpURLConnection.HTTP_PARTIAL,
      contentRange = "bytes 5-9/10",
    )

    downloader(ArrayDeque(listOf(redirect, asset))).download(TEST_URL, destination, 100, 10)

    assertEquals("bytes=5-", redirect.requestHeaders["Range"])
    assertEquals("bytes=5-", asset.requestHeaders["Range"])
    assertEquals("abcdefghij", destination.readText())
  }

  @Test
  fun `rejects a redirect that leaves HTTPS`() {
    val redirect = FakeConnection(
      byteArrayOf(),
      responseCodeValue = HttpURLConnection.HTTP_MOVED_TEMP,
      location = "http://assets.example.test/update.apk",
    )
    val error = runCatching {
      downloader(ArrayDeque(listOf(redirect))).download(
        TEST_URL,
        temporaryFolder.newFile("update.apk.part"),
        100,
        10,
      )
    }.exceptionOrNull()

    assertTrue(error is UpdateProtocolException)
    assertTrue(error?.message.orEmpty().contains("HTTPS"))
  }

  @Test
  fun `retries a temporary HTTP response`() {
    val temporaryFailure = FakeConnection(byteArrayOf(), responseCodeValue = 429)
    val success = FakeConnection("abcdefghij".toByteArray(StandardCharsets.UTF_8))
    val destination = temporaryFolder.newFile("update.apk.part")

    downloader(ArrayDeque(listOf(temporaryFailure, success))).download(TEST_URL, destination, 100, 10)

    assertEquals("abcdefghij", destination.readText())
  }

  @Test
  fun `does not retry a permanent HTTP failure`() {
    val connection = FakeConnection(byteArrayOf(), responseCodeValue = HttpURLConnection.HTTP_NOT_FOUND)
    val destination = temporaryFolder.newFile("update.apk.part")
    val error = runCatching {
      downloader(ArrayDeque(listOf(connection))).download(TEST_URL, destination, 100, 10)
    }.exceptionOrNull()

    assertTrue(error is UpdateProtocolException)
    assertTrue(error?.message.orEmpty().contains("HTTP 404"))
  }

  @Test
  fun `retries truncated metadata`() {
    val first = FakeConnection("abc".toByteArray(), declaredLength = 6)
    val second = FakeConnection("abcdef".toByteArray())
    val result = downloader(ArrayDeque(listOf(first, second))).readText(TEST_URL, 100)

    assertEquals("abcdef", result)
  }

  private fun downloader(connections: ArrayDeque<FakeConnection>): ResumableUpdateDownloader =
    ResumableUpdateDownloader(
      userAgent = "test",
      connectionFactory = { connections.removeFirst() },
      sleeper = {},
    )

  private class FakeConnection(
    private val body: ByteArray,
    private val responseCodeValue: Int = HttpURLConnection.HTTP_OK,
    private val contentRange: String? = null,
    private val location: String? = null,
    private val failAfterBytes: Int? = null,
    private val declaredLength: Long = body.size.toLong(),
  ) : HttpURLConnection(URL(TEST_URL)) {
    val requestHeaders = mutableMapOf<String, String>()

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = responseCodeValue
    override fun getContentLengthLong(): Long = declaredLength
    override fun getHeaderField(name: String?): String? =
      when (name) {
        "Content-Range" -> contentRange
        "Content-Length" -> declaredLength.toString()
        "Location" -> location
        else -> null
      }

    override fun setRequestProperty(key: String, value: String) {
      requestHeaders[key] = value
    }

    override fun getRequestProperty(key: String): String? = requestHeaders[key]

    override fun getInputStream(): InputStream = object : InputStream() {
      private var offset = 0

      override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xff
      }

      override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (failAfterBytes != null && offset >= failAfterBytes) throw SocketException("connection abort")
        if (offset >= body.size) return -1
        val failureLimit = failAfterBytes?.minus(offset) ?: length
        val count = minOf(length, body.size - offset, failureLimit)
        if (count <= 0) throw SocketException("connection abort")
        body.copyInto(buffer, start, offset, offset + count)
        offset += count
        return count
      }
    }
  }

  companion object {
    private const val TEST_URL = "https://example.test/update.apk"
  }
}
