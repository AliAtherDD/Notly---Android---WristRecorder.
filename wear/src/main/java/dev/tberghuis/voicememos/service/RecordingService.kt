package dev.tberghuis.voicememos.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.tberghuis.voicememos.Constants.NOTIFICATION_CHANNEL_ID
import dev.tberghuis.voicememos.Constants.NOTIFICATION_ID
import dev.tberghuis.voicememos.Constants.REQUEST_CODE_LAUNCH_MAIN_ACTIVITY
import dev.tberghuis.voicememos.MainActivity
import dev.tberghuis.voicememos.R
import dev.tberghuis.voicememos.common.logd
import dev.tberghuis.voicememos.data.ConsultationStore
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.formatSecondsToMinutesAndSeconds
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RecorderState {
  data object Idle : RecorderState

  data class Active(
    val consultationId: String,
    /** elapsedRealtime when the current un-paused span started. */
    val spanStartedAt: Long,
    /** Recorded time before the current span (previous spans of this segment). */
    val accumulatedMs: Long,
    val paused: Boolean,
    /** Another app took the microphone (Android 10+ concurrent capture rules). */
    val micSilenced: Boolean = false,
  ) : RecorderState {
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) =
      accumulatedMs + if (paused) 0 else now - spanStartedAt
  }

  data class Failed(val consultationId: String?, val message: String) : RecorderState
}

/**
 * Foreground (microphone) service that owns the MediaRecorder for one consultation segment.
 * Driven by intents so it survives the activity being swiped away; state is observed through
 * [state]. Output is AAC-LC in MPEG-4 (.m4a), matching the phone app's recordings (audio/mp4).
 */
class RecordingService : LifecycleService() {
  private var recorder: MediaRecorder? = null
  private var outputFile: File? = null
  private var recordingId: String? = null
  private var tickerJob: Job? = null
  private val store by lazy { ConsultationStore.get(this) }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    when (intent?.action) {
      ACTION_START -> start(intent.getStringExtra(EXTRA_CONSULTATION_ID) ?: return START_NOT_STICKY)
      ACTION_PAUSE -> pause()
      ACTION_RESUME -> resume()
      ACTION_FINISH -> lifecycleScope.launch { finish() }
    }
    return START_NOT_STICKY
  }

  private fun start(consultationId: String) {
    if (_state.value is RecorderState.Active) return
    // Must be called promptly after startForegroundService(). Android 14+ throws if the
    // microphone permission is missing, so fail gracefully instead of crashing.
    val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    try {
      startInForeground()
    } catch (e: Exception) {
      logd("startForeground failed $e")
      fail(consultationId, getString(if (permitted) R.string.error_recorder else R.string.error_mic_permission))
      return
    }
    if (!permitted) {
      fail(consultationId, getString(R.string.error_mic_permission))
      return
    }

    val id = UUID.randomUUID().toString()
    val file = store.newSegmentFile(consultationId, id)
    try {
      val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
      r.setAudioSource(MediaRecorder.AudioSource.MIC)
      r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      r.setAudioChannels(1)
      r.setAudioSamplingRate(SAMPLE_RATE)
      r.setAudioEncodingBitRate(BIT_RATE)
      r.setOutputFile(file.absolutePath)
      r.setOnErrorListener { _, what, extra ->
        logd("MediaRecorder error what=$what extra=$extra")
        lifecycleScope.launch { finish(errorMessage = getString(R.string.error_recorder)) }
      }
      r.prepare()
      r.start()
      recorder = r
      outputFile = file
      recordingId = id
    } catch (e: Exception) {
      logd("start recording failed $e")
      file.delete()
      fail(consultationId, getString(R.string.error_recorder))
      return
    }

    store.update(consultationId) { it.copy(state = SyncState.RECORDING, lastError = null) }
    _state.value = RecorderState.Active(
      consultationId = consultationId,
      spanStartedAt = SystemClock.elapsedRealtime(),
      accumulatedMs = 0,
      paused = false,
    )
    startTicker()
  }

  private fun pause() {
    val s = _state.value as? RecorderState.Active ?: return
    if (s.paused) return
    try {
      recorder?.pause()
      _state.value = s.copy(paused = true, accumulatedMs = s.elapsedMs())
    } catch (e: Exception) {
      logd("pause failed $e")
    }
  }

  private fun resume() {
    val s = _state.value as? RecorderState.Active ?: return
    if (!s.paused) return
    try {
      recorder?.resume()
      _state.value = s.copy(paused = false, spanStartedAt = SystemClock.elapsedRealtime())
    } catch (e: Exception) {
      logd("resume failed $e")
    }
  }

  /** Stops and finalizes the segment, then hands it to the store. */
  private suspend fun finish(errorMessage: String? = null) {
    val s = _state.value as? RecorderState.Active ?: return
    val r = recorder ?: return
    recorder = null
    tickerJob?.cancel()
    val durationMs = s.elapsedMs()
    val file = outputFile
    val id = recordingId

    val finalized = try {
      r.stop()
      true
    } catch (e: RuntimeException) {
      // Thrown when stop() is called before any audio was captured; the file is invalid.
      logd("stop failed $e")
      false
    } finally {
      r.release()
    }

    withContext(Dispatchers.IO) {
      if (finalized && file != null && id != null && file.length() > 0) {
        store.addSegment(s.consultationId, id, file, durationMs)
      } else {
        file?.delete()
      }
      store.update(s.consultationId) { c ->
        if (c.segments.isEmpty()) c.copy(state = SyncState.READY, lastError = errorMessage ?: getString(R.string.error_no_audio))
        else c.copy(state = SyncState.READY, lastError = errorMessage)
      }
    }

    _state.value = if (errorMessage != null) RecorderState.Failed(s.consultationId, errorMessage) else RecorderState.Idle
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun fail(consultationId: String?, message: String) {
    _state.value = RecorderState.Failed(consultationId, message)
    if (consultationId != null) {
      store.update(consultationId) { it.copy(state = SyncState.READY, lastError = message) }
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun startTicker() {
    tickerJob?.cancel()
    tickerJob = lifecycleScope.launch {
      while (isActive) {
        delay(1000)
        val s = _state.value as? RecorderState.Active ?: break
        val silenced = recorder?.activeRecordingConfiguration?.isClientSilenced == true
        if (silenced != s.micSilenced) _state.value = s.copy(micSilenced = silenced)
        updateOngoingStatus(s)
      }
    }
  }

  private fun updateOngoingStatus(s: RecorderState.Active) {
    // Updating the ongoing chip posts a notification, which the user may have disallowed.
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
      != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val label = if (s.paused) getString(R.string.recording_paused) else "REC"
    OngoingActivity.recoverOngoingActivity(applicationContext)?.update(
      applicationContext,
      Status.Builder()
        .addTemplate("$label ${formatSecondsToMinutesAndSeconds((s.elapsedMs() / 1000).toInt())}")
        .build(),
    )
  }

  private fun startInForeground() {
    val notification = buildNotification()
    startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
  }

  private fun buildNotification(): Notification {
    val activityPendingIntent = PendingIntent.getActivity(
      this,
      REQUEST_CODE_LAUNCH_MAIN_ACTIVITY,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
      .setContentTitle(getString(R.string.recording_consultation))
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      // Lock-screen safe: no patient information is ever shown here.
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .addAction(R.drawable.ic_recording, getString(R.string.open_notly), activityPendingIntent)

    OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
      .setStaticIcon(R.drawable.wear_ongoing_0)
      .setAnimatedIcon(R.drawable.wear_ongoing_animation)
      .setTouchIntent(activityPendingIntent)
      .setStatus(Status.Builder().addTemplate("REC 00:00").build())
      .build()
      .apply(applicationContext)
    return builder.build()
  }

  override fun onDestroy() {
    // Service torn down while still recording (should not happen for a foreground service):
    // release the mic; the unfinalized file is cleaned up by ConsultationStore.recover().
    recorder?.release()
    recorder = null
    super.onDestroy()
  }

  companion object {
    const val SAMPLE_RATE = 44100
    const val BIT_RATE = 64000

    private const val ACTION_START = "notly.recording.START"
    private const val ACTION_PAUSE = "notly.recording.PAUSE"
    private const val ACTION_RESUME = "notly.recording.RESUME"
    private const val ACTION_FINISH = "notly.recording.FINISH"
    private const val EXTRA_CONSULTATION_ID = "consultationId"

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    fun clearFailure() {
      if (_state.value is RecorderState.Failed) _state.value = RecorderState.Idle
    }

    fun start(context: Context, consultationId: String) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, RecordingService::class.java)
          .setAction(ACTION_START)
          .putExtra(EXTRA_CONSULTATION_ID, consultationId),
      )
    }

    fun pause(context: Context) = send(context, ACTION_PAUSE)
    fun resume(context: Context) = send(context, ACTION_RESUME)
    fun finish(context: Context) = send(context, ACTION_FINISH)

    private fun send(context: Context, action: String) {
      if (_state.value !is RecorderState.Active) return
      context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }
  }
}
