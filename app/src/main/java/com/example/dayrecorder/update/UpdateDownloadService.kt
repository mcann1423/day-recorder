package com.example.dayrecorder.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dayrecorder.MainActivity
import com.example.dayrecorder.R
import com.example.dayrecorder.updatecore.UpdateTransferProgress
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class UpdateDownloadService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val store by lazy { UpdateDownloadStore(this) }
  private var downloadJob: Job? = null
  private var activeRelease: UpdateRelease? = null
  private var wakeLock: PowerManager.WakeLock? = null
  @Volatile private var workFinished = false

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val release = intent?.takeIf { it.action == ACTION_DOWNLOAD }?.toRelease()
    if (release == null) {
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    if (downloadJob?.isActive == true) return START_NOT_STICKY

    activeRelease = release
    workFinished = false
    store.markStarting(release)
    try {
      startForeground(
        NOTIFICATION_ID,
        notification("Preparing download…", ongoing = true),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
      acquireWakeLock()
    } catch (error: Throwable) {
      workFinished = true
      store.markError(release, error.message ?: "Could not keep the update download active")
      releaseWakeLock()
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    downloadJob = serviceScope.launch { download(release, startId) }
    return START_NOT_STICKY
  }

  private suspend fun download(release: UpdateRelease, startId: Int) {
    var finalMessage = "Update download paused. Tap Install to resume."
    try {
      val apk = withTimeout(DOWNLOAD_TIMEOUT_MILLIS) {
        runInterruptible {
          GitHubUpdateClient(this@UpdateDownloadService).downloadAndVerify(release) { progress ->
            publishProgress(release, progress)
          }
        }
      }
      check(apk.isFile) { "Verified update file is missing" }
      store.markReady(release)
      finalMessage = "Update verified. Open Day Recorder and tap Install again."
    } catch (_: TimeoutCancellationException) {
      finalMessage = "Update download timed out. Tap Install to resume."
      store.markError(release, finalMessage)
    } catch (error: CancellationException) {
      finalMessage = if (workFinished) finalMessage else "Update download paused. Tap Install to resume."
      if (!workFinished) store.markError(release, finalMessage)
    } catch (error: Throwable) {
      finalMessage = error.message ?: "Update download failed"
      store.markError(release, finalMessage)
    } finally {
      workFinished = true
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_DETACH)
      getSystemService(NotificationManager::class.java).notify(
        NOTIFICATION_ID,
        notification(finalMessage, ongoing = false),
      )
      stopSelfResult(startId)
    }
  }

  private fun publishProgress(release: UpdateRelease, progress: UpdateTransferProgress) {
    val message = progress.displayText()
    store.markProgress(release, progress)
    getSystemService(NotificationManager::class.java).notify(
      NOTIFICATION_ID,
      notification(message, ongoing = true, progress = progress),
    )
  }

  private fun acquireWakeLock() {
    wakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:UpdateDownload")
      .apply {
        setReferenceCounted(false)
        acquire(WAKE_LOCK_TIMEOUT_MILLIS)
      }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  private fun notification(
    message: String,
    ongoing: Boolean,
    progress: UpdateTransferProgress? = null,
  ): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(if (ongoing) "Downloading Day Recorder update" else "Day Recorder update")
      .setContentText(message)
      .setContentIntent(openAppPendingIntent())
      .setOngoing(ongoing)
      .setAutoCancel(!ongoing)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    val total = progress?.totalBytes
    if (ongoing && total != null && total > 0L) {
      val max = (total / PROGRESS_UNIT_BYTES).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      val current = (progress.bytesDownloaded / PROGRESS_UNIT_BYTES).coerceIn(0L, max.toLong()).toInt()
      builder.setProgress(max, current, false)
    } else if (ongoing) {
      builder.setProgress(0, 0, true)
    }
    return builder.build()
  }

  private fun openAppPendingIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun createNotificationChannel() {
    val channel = NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
      description = "Shows progress for user-requested app updates"
      setSound(null, null)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  override fun onDestroy() {
    if (!workFinished) {
      activeRelease?.let { store.markError(it, "Update download paused. Tap Install to resume.") }
    }
    downloadJob?.cancel()
    serviceScope.cancel()
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val ACTION_DOWNLOAD = "com.example.dayrecorder.action.DOWNLOAD_UPDATE"
    private const val EXTRA_VERSION_NAME = "version-name"
    private const val EXTRA_VERSION_CODE = "version-code"
    private const val EXTRA_APK_NAME = "apk-name"
    private const val EXTRA_APK_URL = "apk-url"
    private const val EXTRA_CHECKSUM_URL = "checksum-url"
    private const val EXTRA_APK_SIZE = "apk-size"
    private const val CHANNEL_ID = "app-updates"
    private const val NOTIFICATION_ID = 7102
    private const val PROGRESS_UNIT_BYTES = 1_024L
    private const val DOWNLOAD_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    private const val WAKE_LOCK_TIMEOUT_MILLIS = DOWNLOAD_TIMEOUT_MILLIS + 30_000L

    fun start(context: Context, release: UpdateRelease) {
      val intent = Intent(context, UpdateDownloadService::class.java)
        .setAction(ACTION_DOWNLOAD)
        .putExtra(EXTRA_VERSION_NAME, release.versionName)
        .putExtra(EXTRA_VERSION_CODE, release.versionCode)
        .putExtra(EXTRA_APK_NAME, release.apkName)
        .putExtra(EXTRA_APK_URL, release.apkUrl)
        .putExtra(EXTRA_CHECKSUM_URL, release.checksumUrl)
        .putExtra(EXTRA_APK_SIZE, release.apkSize ?: -1L)
      ContextCompat.startForegroundService(context, intent)
    }

    fun cancelCompletedNotification(context: Context) {
      context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun Intent.toRelease(): UpdateRelease? {
      val versionName = getStringExtra(EXTRA_VERSION_NAME)?.takeIf(String::isNotBlank) ?: return null
      val versionCode = getLongExtra(EXTRA_VERSION_CODE, -1L).takeIf { it > 0L } ?: return null
      val apkName = getStringExtra(EXTRA_APK_NAME)?.takeIf(String::isNotBlank) ?: return null
      val apkUrl = getStringExtra(EXTRA_APK_URL)?.takeIf(String::isNotBlank) ?: return null
      val checksumUrl = getStringExtra(EXTRA_CHECKSUM_URL)?.takeIf(String::isNotBlank) ?: return null
      return UpdateRelease(
        versionName = versionName,
        versionCode = versionCode,
        releaseNotes = "",
        apkName = File(apkName).name.takeIf { it == apkName } ?: return null,
        apkUrl = apkUrl,
        checksumUrl = checksumUrl,
        apkSize = getLongExtra(EXTRA_APK_SIZE, -1L).takeIf { it > 0L },
      )
    }
  }
}
