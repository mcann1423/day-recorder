package com.example.dayrecorder.updatecore

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.SSLException

enum class UpdateTransferStage {
  CONNECTING,
  DOWNLOADING,
  RETRYING,
  VERIFYING,
  READY,
}

data class UpdateTransferProgress(
  val stage: UpdateTransferStage,
  val bytesDownloaded: Long = 0L,
  val totalBytes: Long? = null,
  val attempt: Int = 1,
  val maxAttempts: Int = 3,
) {
  fun displayText(): String =
    when (stage) {
      UpdateTransferStage.CONNECTING -> "Connecting…"
      UpdateTransferStage.DOWNLOADING -> {
        val downloaded = formatMegabytes(bytesDownloaded)
        val total = totalBytes?.let(::formatMegabytes)
        if (total == null) "Downloading $downloaded…" else "Downloading $downloaded / $total…"
      }
      UpdateTransferStage.RETRYING ->
        "Connection interrupted at ${formatMegabytes(bytesDownloaded)}. Retrying ${attempt.coerceAtMost(maxAttempts)} of $maxAttempts…"
      UpdateTransferStage.VERIFYING -> "Verifying update…"
      UpdateTransferStage.READY -> "Update verified and ready to install"
    }

  private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
}

class UpdateNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

class UpdateProtocolException(message: String) : IOException(message)

class ResumableUpdateDownloader(
  private val userAgent: String,
  private val maxAttempts: Int = 3,
  private val requireHttps: Boolean = true,
  private val connectionFactory: (URL) -> HttpURLConnection = { url ->
    url.openConnection() as HttpURLConnection
  },
  private val sleeper: (Long) -> Unit = Thread::sleep,
) {
  fun readText(url: String, maxBytes: Long): String =
    retrying("Update server connection failed") {
      connectFollowingRedirects(url).useConnection { connection ->
        val responseCode = connection.responseCode
        validateResponse(responseCode)
        val declaredLength = connection.contentLengthLong.takeIf { it >= 0L }
        if (declaredLength != null && declaredLength > maxBytes) {
          throw UpdateProtocolException("Update metadata is larger than allowed")
        }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw UpdateProtocolException("Update metadata is larger than allowed")
            output.write(buffer, 0, count)
          }
          if (declaredLength != null && total != declaredLength) {
            throw EOFException("Update metadata connection ended early")
          }
        }
        output.toString(Charsets.UTF_8.name())
      }
    }

  fun download(
    url: String,
    destination: File,
    maxBytes: Long,
    expectedBytes: Long?,
    onProgress: (UpdateTransferProgress) -> Unit = {},
  ) {
    require(maxBytes > 0L)
    require(expectedBytes == null || expectedBytes in 1..maxBytes) { "Release APK size is invalid" }
    destination.parentFile?.mkdirs()
    if (destination.length() > maxBytes || (expectedBytes != null && destination.length() > expectedBytes)) {
      RandomAccessFile(destination, "rw").use { it.setLength(0L) }
    }
    if (expectedBytes != null && destination.length() == expectedBytes) {
      onProgress(UpdateTransferProgress(UpdateTransferStage.DOWNLOADING, expectedBytes, expectedBytes))
      return
    }

    var lastFailure: IOException? = null
    for (attempt in 1..maxAttempts) {
      try {
        downloadAttempt(url, destination, maxBytes, expectedBytes, attempt, onProgress)
        return
      } catch (error: IOException) {
        if (!isRetryable(error) || attempt == maxAttempts) {
          if (error is UpdateProtocolException) throw error
          throw UpdateNetworkException(
            "Connection interrupted after $attempt attempt${if (attempt == 1) "" else "s"}. Check Wi-Fi and try again.",
            error,
          )
        }
        lastFailure = error
        onProgress(
          UpdateTransferProgress(
            stage = UpdateTransferStage.RETRYING,
            bytesDownloaded = destination.length(),
            totalBytes = expectedBytes,
            attempt = attempt + 1,
            maxAttempts = maxAttempts,
          ),
        )
        sleeper(BACKOFF_MILLIS * attempt)
      }
    }
    throw UpdateNetworkException("Update download failed", lastFailure)
  }

  private fun downloadAttempt(
    url: String,
    destination: File,
    maxBytes: Long,
    expectedBytes: Long?,
    attempt: Int,
    onProgress: (UpdateTransferProgress) -> Unit,
  ) {
    val requestedOffset = destination.length()
    onProgress(
      UpdateTransferProgress(
        UpdateTransferStage.CONNECTING,
        requestedOffset,
        expectedBytes,
        attempt,
        maxAttempts,
      ),
    )
    connectFollowingRedirects(url, requestedOffset.takeIf { it > 0L }).useConnection { connection ->
      val responseCode = connection.responseCode
      if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && expectedBytes != null && requestedOffset == expectedBytes) return
      validateResponse(responseCode)

      val append = responseCode == HttpURLConnection.HTTP_PARTIAL && requestedOffset > 0L
      if (responseCode == HttpURLConnection.HTTP_PARTIAL) validateContentRange(connection, requestedOffset, expectedBytes)
      val startOffset = if (append) requestedOffset else 0L
      val responseBytes = connection.contentLengthLong.takeIf { it >= 0L }
      val responseTotal = responseBytes?.let { startOffset + it }
      val totalBytes = expectedBytes ?: responseTotal
      if (responseTotal != null && responseTotal > maxBytes) {
        throw UpdateProtocolException("Downloaded APK is larger than allowed")
      }

      RandomAccessFile(destination, "rw").use { output ->
        if (append) output.seek(startOffset) else output.setLength(0L)
        connection.inputStream.use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var downloaded = startOffset
          var lastReported = downloaded
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            downloaded += count
            if (downloaded > maxBytes || (expectedBytes != null && downloaded > expectedBytes)) {
              throw UpdateProtocolException("Downloaded APK is larger than allowed")
            }
            output.write(buffer, 0, count)
            if (downloaded - lastReported >= PROGRESS_INTERVAL_BYTES) {
              onProgress(
                UpdateTransferProgress(
                  UpdateTransferStage.DOWNLOADING,
                  downloaded,
                  totalBytes,
                  attempt,
                  maxAttempts,
                ),
              )
              lastReported = downloaded
            }
          }
          output.fd.sync()
          onProgress(
            UpdateTransferProgress(
              UpdateTransferStage.DOWNLOADING,
              downloaded,
              totalBytes,
              attempt,
              maxAttempts,
            ),
          )
        }
      }
      if (responseTotal != null && destination.length() != responseTotal) {
        throw EOFException("Update connection ended before the response was complete")
      }
    }
    if (expectedBytes != null && destination.length() != expectedBytes) {
      throw EOFException("Update connection ended before the APK was complete")
    }
  }

  private fun validateContentRange(connection: HttpURLConnection, expectedStart: Long, expectedBytes: Long?) {
    val header = connection.getHeaderField("Content-Range")
      ?: throw UpdateProtocolException("Update server returned an invalid resume response")
    val match = CONTENT_RANGE.matchEntire(header.trim())
      ?: throw UpdateProtocolException("Update server returned an invalid resume response")
    val actualStart = match.groupValues[1].toLong()
    val actualTotal = match.groupValues[3].takeUnless { it == "*" }?.toLong()
    if (actualStart != expectedStart || (expectedBytes != null && actualTotal != null && actualTotal != expectedBytes)) {
      throw UpdateProtocolException("Update server returned the wrong resume range")
    }
  }

  private fun configure(connection: HttpURLConnection) {
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = READ_TIMEOUT_MILLIS
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", userAgent)
  }

  private fun connectFollowingRedirects(url: String, rangeStart: Long? = null): HttpURLConnection {
    var uri = validateUri(URI(url))
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
      val connection = connectionFactory(uri.toURL())
      configure(connection)
      if (rangeStart != null) connection.setRequestProperty("Range", "bytes=$rangeStart-")
      val responseCode = connection.responseCode
      if (responseCode !in REDIRECT_CODES) return connection
      val location = connection.getHeaderField("Location")
      connection.disconnect()
      if (location.isNullOrBlank()) throw UpdateProtocolException("Update server returned a redirect without a location")
      if (redirectCount == MAX_REDIRECTS) throw UpdateProtocolException("Update server returned too many redirects")
      uri = validateUri(uri.resolve(location))
    }
    throw UpdateProtocolException("Update server returned too many redirects")
  }

  private fun validateUri(uri: URI): URI {
    if (requireHttps && !uri.scheme.equals("https", ignoreCase = true)) {
      throw UpdateProtocolException("Update URL must use HTTPS")
    }
    return uri
  }

  private fun validateResponse(responseCode: Int) {
    when {
      responseCode in 200..299 -> return
      responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT || responseCode == 429 || responseCode in 500..599 ->
        throw IOException("Update server temporarily returned HTTP $responseCode")
      else -> throw UpdateProtocolException("Update server returned HTTP $responseCode")
    }
  }

  private fun isRetryable(error: IOException): Boolean =
    error !is UpdateProtocolException && error !is SSLException

  private fun <T> retrying(message: String, block: (Int) -> T): T {
    var lastFailure: IOException? = null
    for (attempt in 1..maxAttempts) {
      try {
        return block(attempt)
      } catch (error: IOException) {
        if (!isRetryable(error) || attempt == maxAttempts) {
          if (error is UpdateProtocolException) throw error
          throw UpdateNetworkException("$message after $attempt attempt${if (attempt == 1) "" else "s"}.", error)
        }
        lastFailure = error
        sleeper(BACKOFF_MILLIS * attempt)
      }
    }
    throw UpdateNetworkException(message, lastFailure)
  }

  private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
    try {
      block(this)
    } finally {
      disconnect()
    }

  companion object {
    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 60_000
    private const val BACKOFF_MILLIS = 1_000L
    private const val PROGRESS_INTERVAL_BYTES = 256L * 1_024L
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
  }
}
