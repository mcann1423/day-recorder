package com.example.dayrecorder.phone

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneStorageSnapshot(
  val retention: PhoneRetentionStatus,
  val inaccessibleLocations: Int,
)

private data class StoredRecording(
  val uri: Uri,
  val info: PhoneRecordingInfo,
)

class PhoneRecordingStorage(private val context: Context) {
  private val resolver = context.contentResolver
  private val prefs = context.getSharedPreferences(PhoneContract.PREFS, Context.MODE_PRIVATE)
  private val policy = PhoneRetentionPolicy()

  fun currentLocationLabel(): String {
    if (currentTreeUri() == null) return DEFAULT_LOCATION_LABEL
    return prefs.getString(PhoneContract.KEY_STORAGE_TREE_LABEL, null)?.let { "Selected folder: $it" }
      ?: "Selected folder"
  }

  fun isUsingCustomLocation(): Boolean = currentTreeUri() != null

  fun selectLocation(uri: Uri) {
    resolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    val known = knownTreeUris().toMutableSet().apply { add(uri.toString()) }
    val label = DocumentFile.fromTreeUri(context, uri)?.name ?: "Custom folder"
    prefs.edit()
      .putString(PhoneContract.KEY_STORAGE_TREE_URI, uri.toString())
      .putString(PhoneContract.KEY_STORAGE_TREE_LABEL, label)
      .putStringSet(PhoneContract.KEY_KNOWN_STORAGE_TREE_URIS, known)
      .apply()
  }

  fun useDefaultLocation() {
    prefs.edit().remove(PhoneContract.KEY_STORAGE_TREE_URI).apply()
  }

  fun saveVerifiedAudio(
    input: InputStream,
    fileName: String,
    expectedSize: Long,
    expectedHash: String,
    recordedAt: Long,
  ): Boolean = synchronized(STORAGE_LOCK) {
    check(expectedSize in 1..MAX_RECORDING_BYTES) { "Recording size is invalid" }
    check(expectedHash.matches(SHA_256)) { "Recording checksum is invalid" }
    val staged = stageAndVerify(input, expectedSize, expectedHash)
    return try {
      val tree = currentTreeUri()
      if (tree == null) saveToMediaStore(staged, fileName, recordedAt) else saveToTree(tree, staged, fileName, recordedAt)
    } finally {
      staged.delete()
    }
  }

  fun snapshot(now: Long = System.currentTimeMillis()): PhoneStorageSnapshot = synchronized(STORAGE_LOCK) {
    val recordings = mutableListOf<StoredRecording>()
    recordings += defaultRecordings()
    var inaccessible = 0
    knownTreeUris().forEach { value ->
      val tree = runCatching { Uri.parse(value) }.getOrNull()
      val root = tree?.let { DocumentFile.fromTreeUri(context, it) }
      if (root == null || !root.canRead()) {
        inaccessible += 1
      } else {
        runCatching { treeRecordings(root) }
          .onSuccess { recordings += it }
          .onFailure { inaccessible += 1 }
      }
    }
    val unique = recordings.distinctBy { it.info.name }
    return PhoneStorageSnapshot(
      retention = policy.evaluate(unique.map(StoredRecording::info), now),
      inaccessibleLocations = inaccessible,
    )
  }

  fun defaultRecordingUris(): List<Uri> = synchronized(STORAGE_LOCK) {
    defaultRecordings().map(StoredRecording::uri)
  }

  fun purgeCustomRecordings(): Int = synchronized(STORAGE_LOCK) {
    val recordings = mutableListOf<StoredRecording>().apply {
      knownTreeUris().forEach { value ->
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(value)) }.getOrNull()
        if (root != null && root.canRead()) runCatching { treeRecordings(root) }.onSuccess(::addAll)
      }
    }.distinctBy { it.uri.toString() }
    return recordings.count { recording ->
      runCatching { resolver.delete(recording.uri, null, null) > 0 }.getOrDefault(false)
    }
  }

  fun purgeLegacyAll(): Int = synchronized(STORAGE_LOCK) {
    val recordings = defaultRecordings() + knownTreeUris().flatMap { value ->
      val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(value)) }.getOrNull()
      if (root != null && root.canRead()) runCatching { treeRecordings(root) }.getOrDefault(emptyList())
      else emptyList()
    }
    recordings.distinctBy { it.uri.toString() }.count { recording ->
      runCatching { resolver.delete(recording.uri, null, null) > 0 }.getOrDefault(false)
    }
  }

  private fun stageAndVerify(input: InputStream, expectedSize: Long, expectedHash: String): File {
    val directory = File(context.cacheDir, "received-audio").also { it.mkdirs() }
    directory.listFiles()?.forEach(File::delete)
    val staged = File.createTempFile("recording-", ".partial", directory)
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      var copied = 0L
      staged.outputStream().use { output ->
        DigestInputStream(input, digest).use { source ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count > 0) {
              copied += count
              check(copied <= MAX_RECORDING_BYTES) { "Recording is larger than allowed" }
              output.write(buffer, 0, count)
            }
          }
        }
      }
      val actualHash = digest.digest().toHex()
      check(copied == expectedSize && actualHash == expectedHash) { "Recording verification failed" }
      return staged
    } catch (error: Throwable) {
      staged.delete()
      throw error
    }
  }

  private fun saveToMediaStore(staged: File, fileName: String, recordedAt: Long): Boolean {
    val day = checkNotNull(DAY_FORMAT.get()).format(Date(recordedAt))
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
      put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_MIME_TYPE)
      put(MediaStore.Audio.Media.RELATIVE_PATH, "$DEFAULT_RELATIVE_PATH/$day")
      put(MediaStore.Audio.Media.IS_PENDING, 1)
      put(MediaStore.Audio.Media.DATE_ADDED, recordedAt / 1_000L)
    }
    resolver.delete(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
      "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
      arrayOf("$DEFAULT_RELATIVE_PATH/%", fileName),
    )
    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
      resolver.openOutputStream(uri, "w")!!.use { output -> staged.inputStream().use { it.copyTo(output) } }
      resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
      true
    } catch (error: Throwable) {
      resolver.delete(uri, null, null)
      throw error
    }
  }

  private fun saveToTree(tree: Uri, staged: File, fileName: String, recordedAt: Long): Boolean {
    val root = DocumentFile.fromTreeUri(context, tree) ?: error("Selected storage folder is unavailable")
    check(root.canWrite()) { "Selected storage folder is not writable" }
    val day = checkNotNull(DAY_FORMAT.get()).format(Date(recordedAt))
    val dayFolder = root.findFile(day)?.takeIf(DocumentFile::isDirectory) ?: root.createDirectory(day)
      ?: error("Could not create the recording date folder")
    dayFolder.findFile(fileName)?.delete()
    val destination = dayFolder.createFile(AUDIO_MIME_TYPE, fileName)
      ?: error("Could not create the recording file")
    if (destination.name != fileName) {
      destination.delete()
      error("The selected storage provider changed the recording filename")
    }
    return try {
      resolver.openOutputStream(destination.uri, "w")!!.use { output ->
        staged.inputStream().use { it.copyTo(output) }
      }
      true
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
  }

  private fun defaultRecordings(): List<StoredRecording> {
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.DATE_MODIFIED,
    )
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("$DEFAULT_RELATIVE_PATH/%", "day-%.m4a")
    return runCatching {
      resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        buildList {
          while (cursor.moveToNext()) {
            val name = cursor.getString(nameColumn)
            if (!RECORDING_NAME.matches(name)) continue
            val fallback = cursor.getLong(modifiedColumn) * 1_000L
            add(
              StoredRecording(
                uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)),
                info = PhoneRecordingInfo(name, cursor.getLong(sizeColumn), recordedAt(name, fallback)),
              ),
            )
          }
        }
      }.orEmpty()
    }.getOrDefault(emptyList())
  }

  private fun treeRecordings(root: DocumentFile): List<StoredRecording> = buildList {
    root.listFiles().forEach { child ->
      if (child.isDirectory) {
        child.listFiles().forEach { file -> addTreeFile(file) }
      } else {
        addTreeFile(child)
      }
    }
  }

  private fun MutableList<StoredRecording>.addTreeFile(file: DocumentFile) {
    val name = file.name.orEmpty()
    if (!file.isFile || !RECORDING_NAME.matches(name)) return
    add(StoredRecording(file.uri, PhoneRecordingInfo(name, file.length(), recordedAt(name, file.lastModified()))))
  }

  private fun recordedAt(name: String, fallback: Long): Long =
    runCatching {
      checkNotNull(FILE_TIME_FORMAT.get()).parse(name.removePrefix("day-").removeSuffix(".m4a"))?.time
    }
      .getOrNull() ?: fallback

  private fun currentTreeUri(): Uri? =
    prefs.getString(PhoneContract.KEY_STORAGE_TREE_URI, null)?.let(Uri::parse)

  private fun knownTreeUris(): Set<String> =
    prefs.getStringSet(PhoneContract.KEY_KNOWN_STORAGE_TREE_URIS, emptySet())?.toSet().orEmpty()

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

  companion object {
    const val DEFAULT_LOCATION_LABEL = "Internal storage/Music/Day Recorder"
    private const val DEFAULT_RELATIVE_PATH = "Music/Day Recorder"
    private const val AUDIO_MIME_TYPE = "audio/mp4"
    private const val MAX_RECORDING_BYTES = 100L * 1_024L * 1_024L
    private val STORAGE_LOCK = Any()
    private val SHA_256 = Regex("[0-9a-f]{64}")
    private val RECORDING_NAME = Regex("day-[0-9]{8}-[0-9]{6}\\.m4a")
    private val DAY_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val FILE_TIME_FORMAT = ThreadLocal.withInitial {
      SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }
    }
  }
}
