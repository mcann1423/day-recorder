package com.example.dayrecorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dayrecorder.complication.RecorderComplicationUpdates
import com.example.dayrecorder.transfer.WatchTransferQueue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {
  @Volatile private var requestedState = RecorderContract.STATE_IDLE
  private var worker: Thread? = null
  private val prefs by lazy { getSharedPreferences(RecorderContract.PREFS, MODE_PRIVATE) }
  private val transferQueue by lazy { WatchTransferQueue(this) }
  private val batteryPolicy = BatteryPolicy()
  private val dailyTransferPolicy = DailyTransferPolicy()
  private var nextBatteryCheckAt = Long.MAX_VALUE
  private var scheduledTransferAt = Long.MAX_VALUE
  @Volatile private var activeChunk: File? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      RecorderContract.ACTION_START -> startDailySession()
      RecorderContract.ACTION_PAUSE -> setRequestedState(RecorderContract.STATE_PAUSED)
      RecorderContract.ACTION_RESUME -> setRequestedState(RecorderContract.STATE_RECORDING)
      RecorderContract.ACTION_STOP -> stopDailySession()
    }
    return START_NOT_STICKY
  }

  private fun startDailySession() {
    if (worker?.isAlive == true) {
      setRequestedState(RecorderContract.STATE_RECORDING)
      return
    }
    requestedState = RecorderContract.STATE_RECORDING
    val now = SystemClock.elapsedRealtime()
    nextBatteryCheckAt = now
    val transferDelay = dailyTransferPolicy.delayUntilTransfer(System.currentTimeMillis())
    scheduledTransferAt = if (transferDelay == Long.MAX_VALUE) Long.MAX_VALUE else now + transferDelay
    prefs.edit()
      .putString(RecorderContract.KEY_STATE, requestedState)
      .putLong(RecorderContract.KEY_SESSION_STARTED_AT, System.currentTimeMillis())
      .putInt(RecorderContract.KEY_CHUNK_COUNT, 0)
      .remove(RecorderContract.KEY_LAST_ERROR)
      .remove(RecorderContract.KEY_STOP_REASON)
      .apply()
    RecorderComplicationUpdates.request(this)
    startForegroundNotification()
    worker = Thread(::recordSession, "day-recorder").also { it.start() }
  }

  private fun setRequestedState(state: String) {
    if (worker?.isAlive != true) return
    requestedState = state
    prefs.edit().putString(RecorderContract.KEY_STATE, state).apply()
    RecorderComplicationUpdates.request(this)
    updateNotification()
    worker?.interrupt()
  }

  private fun stopDailySession() {
    requestedState = RecorderContract.STATE_IDLE
    prefs.edit().putString(RecorderContract.KEY_STOP_REASON, RecorderContract.STOP_REASON_USER).apply()
    worker?.interrupt()
    prefs.edit().putString(RecorderContract.KEY_STATE, RecorderContract.STATE_IDLE).apply()
    RecorderComplicationUpdates.request(this)
  }

  private fun recordSession() {
    val sessionDeadline = SystemClock.elapsedRealtime() + RecorderContract.SESSION_LIMIT_MS
    try {
      while (
        requestedState != RecorderContract.STATE_IDLE &&
          SystemClock.elapsedRealtime() < sessionDeadline
      ) {
        maybeStopForLowBattery()
        maybeQueueScheduledTransfer()
        if (requestedState == RecorderContract.STATE_IDLE) break
        if (requestedState == RecorderContract.STATE_PAUSED) {
          val nextWakeAt = minOf(nextBatteryCheckAt, scheduledTransferAt, sessionDeadline)
          val waitMs = (nextWakeAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
          try {
            Thread.sleep(waitMs)
          } catch (_: InterruptedException) {
            // State changes interrupt the wait so Pause/Resume/Stop remain immediate.
          }
          continue
        }
        recordUntilPauseOrStop(sessionDeadline)
      }
      if (
        requestedState == RecorderContract.STATE_RECORDING &&
          SystemClock.elapsedRealtime() >= sessionDeadline
      ) {
        prefs.edit().putString(RecorderContract.KEY_STOP_REASON, RecorderContract.STOP_REASON_TIME_LIMIT).apply()
      }
      if (requestedState != RecorderContract.STATE_ERROR) requestedState = RecorderContract.STATE_IDLE
    } catch (_: InterruptedException) {
      requestedState = RecorderContract.STATE_IDLE
    } catch (error: Throwable) {
      requestedState = RecorderContract.STATE_ERROR
      prefs.edit()
        .putString(RecorderContract.KEY_STATE, requestedState)
        .putString(RecorderContract.KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
        .apply()
    } finally {
      prefs.edit().putString(RecorderContract.KEY_STATE, requestedState).apply()
      RecorderComplicationUpdates.request(this)
      queueTransfersNow()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun recordUntilPauseOrStop(sessionDeadline: Long) {
    check(
      ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED,
    ) { "Microphone permission was removed" }
    val currentFile = newChunkFile()
    activeChunk = currentFile
    val recorder = createRecorder(currentFile)
    val chunkStartedAt = SystemClock.elapsedRealtime()
    val policy = ChunkPolicy()
    var started = false
    var closed = false

    try {
      recorder.prepare()
      recorder.start()
      started = true
      while (
        requestedState == RecorderContract.STATE_RECORDING &&
          SystemClock.elapsedRealtime() < sessionDeadline
      ) {
        val beforeWait = SystemClock.elapsedRealtime()
        val chunkDeadline = chunkStartedAt + RecorderContract.CHUNK_DURATION_MS
        val nextWakeAt = minOf(chunkDeadline, nextBatteryCheckAt, scheduledTransferAt, sessionDeadline)
        val waitMs = (nextWakeAt - beforeWait).coerceAtLeast(1L)
        try {
          Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
          // Pause and Stop wake the worker immediately; the loop condition handles the state.
        }
        val now = SystemClock.elapsedRealtime()
        maybeStopForLowBattery(now)
        maybeQueueScheduledTransfer(now)
        val elapsedMs = now - chunkStartedAt
        if (policy.shouldRotate(elapsedMs)) {
          stopAndFinalize(recorder, currentFile, started)
          closed = true
          started = false
          return
        }
      }
    } finally {
      if (!closed) stopAndFinalize(recorder, currentFile, started)
      activeChunk = null
    }
  }

  private fun createRecorder(output: File): MediaRecorder {
    @Suppress("DEPRECATION")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
    return recorder.apply {
      setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
      setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      setAudioChannels(1)
      setAudioSamplingRate(RecorderContract.SAMPLE_RATE)
      setAudioEncodingBitRate(RecorderContract.AAC_BIT_RATE)
      setOutputFile(output.absolutePath)
    }
  }

  private fun stopAndFinalize(recorder: MediaRecorder, file: File, started: Boolean) {
    val stopped = started && runCatching { recorder.stop() }.isSuccess
    recorder.release()
    if (stopped && file.exists() && file.length() > 0L) {
      recordCompletedChunk(file)
    } else {
      file.delete()
    }
    activeChunk = null
  }

  private fun newChunkFile(): File {
    val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DayRecorder")
    check(directory.exists() || directory.mkdirs()) { "Could not create recording directory" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return File(directory, "day-$stamp.m4a.partial")
  }

  private fun recordCompletedChunk(file: File) {
    if (!file.exists() || file.length() == 0L) return
    val completedFile = if (file.name.endsWith(".partial")) {
      File(file.parentFile, file.name.removeSuffix(".partial")).also { destination ->
        check(file.renameTo(destination)) { "Could not finalize ${destination.name}" }
      }
    } else {
      file
    }
    val next = prefs.getInt(RecorderContract.KEY_CHUNK_COUNT, 0) + 1
    prefs.edit()
      .putInt(RecorderContract.KEY_CHUNK_COUNT, next)
      .putString(RecorderContract.KEY_LAST_CHUNK, completedFile.name)
      .apply()
    updateNotification()
  }

  private fun maybeStopForLowBattery(now: Long = SystemClock.elapsedRealtime()) {
    if (now < nextBatteryCheckAt) return
    nextBatteryCheckAt = now + RecorderContract.BATTERY_CHECK_INTERVAL_MS
    val capacity = getSystemService(BatteryManager::class.java)
      .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (!batteryPolicy.shouldStop(capacity)) return
    requestedState = RecorderContract.STATE_IDLE
    prefs.edit()
      .putString(RecorderContract.KEY_STATE, RecorderContract.STATE_IDLE)
      .putString(RecorderContract.KEY_STOP_REASON, RecorderContract.STOP_REASON_LOW_BATTERY)
      .apply()
  }

  private fun queueTransfersNow() {
    val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DayRecorder")
    transferQueue.queueCompletedFiles(directory, activeChunk?.name)
  }

  private fun maybeQueueScheduledTransfer(now: Long = SystemClock.elapsedRealtime()) {
    if (now < scheduledTransferAt || requestedState == RecorderContract.STATE_IDLE) return
    // Mark the daily window consumed before starting asynchronous queue work so a
    // failure cannot create a tight radio-wakeup retry loop during recording.
    scheduledTransferAt = Long.MAX_VALUE
    queueTransfersNow()
  }

  private fun startForegroundNotification() {
    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
  }

  private fun updateNotification() {
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
  }

  private fun notification(): Notification {
    val paused = requestedState == RecorderContract.STATE_PAUSED
    val chunks = prefs.getInt(RecorderContract.KEY_CHUNK_COUNT, 0)
    val toggleAction = if (paused) RecorderContract.ACTION_RESUME else RecorderContract.ACTION_PAUSE
    val toggleLabel = if (paused) "Resume" else "Pause"
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(com.example.dayrecorder.R.drawable.ic_launcher_foreground)
      .setContentTitle(if (paused) "Day recording paused" else "Day recording active")
      .setContentText("$chunks completed chunks")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, toggleLabel, servicePendingIntent(toggleAction, 1))
      .addAction(0, "Stop", servicePendingIntent(RecorderContract.ACTION_STOP, 2))
      .build()
  }

  private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
    PendingIntent.getService(
      this,
      requestCode,
      Intent(this, RecorderService::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(CHANNEL_ID, "Day recording", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shows when the watch microphone is recording"
        setSound(null, null)
      }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL_ID = "day-recording"
    private const val NOTIFICATION_ID = 7101
  }
}
