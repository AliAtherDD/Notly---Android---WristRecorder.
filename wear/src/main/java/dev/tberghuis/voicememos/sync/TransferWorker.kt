package dev.tberghuis.voicememos.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.tberghuis.voicememos.Constants.TRANSFER_NOTIFICATION_CHANNEL_ID
import dev.tberghuis.voicememos.Constants.TRANSFER_NOTIFICATION_ID
import dev.tberghuis.voicememos.R
import dev.tberghuis.voicememos.common.logd
import dev.tberghuis.voicememos.data.AuthStore
import dev.tberghuis.voicememos.data.ConsultationStore
import dev.tberghuis.voicememos.data.Segment
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.data.WatchAuth
import dev.tberghuis.voicememos.data.WatchConsultation
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Sends every QUEUED consultation to the phone, one segment at a time:
 *   1. RPC offer   -> phone validates the credential and de-duplicates by recordingId
 *   2. Channel     -> stream the .m4a
 *   3. RPC verify  -> phone confirms size + SHA-256 and that the file is durably stored
 * Only after (3) is a segment marked delivered; only when all segments are delivered is the
 * audio removed from the watch. Any transient failure leaves the consultation QUEUED and the
 * worker retries with exponential backoff (and immediately when the phone reconnects).
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val store = ConsultationStore.get(context)
  private val authStore = AuthStore.get(context)
  private val messageClient = Wearable.getMessageClient(context)
  private val channelClient = Wearable.getChannelClient(context)
  private val capabilityClient = Wearable.getCapabilityClient(context)

  private sealed interface Outcome {
    data object Delivered : Outcome
    data object Unauthorized : Outcome
    data class Retry(val reason: String) : Outcome
  }

  override suspend fun doWork(): Result {
    if (store.nextToSend() == null) return Result.success()
    val auth = authStore.auth.value ?: return Result.success() // re-run on authorize

    try {
      setForeground(getForegroundInfo())
    } catch (e: Exception) {
      logd("TransferWorker setForeground failed $e") // still try in the background
    }

    val node = findPhone() ?: run {
      logd("TransferWorker phone not reachable")
      return Result.retry()
    }

    while (true) {
      val consultation = store.nextToSend() ?: break
      if (consultation.doctorPublicId != auth.doctor.publicId) {
        store.update(consultation.id) {
          it.copy(state = SyncState.FAILED, lastError = applicationContext.getString(R.string.error_other_account))
        }
        continue
      }
      store.update(consultation.id) { it.copy(state = SyncState.SENDING, lastError = null) }

      var sentBytes = consultation.segments.filter { it.delivered }.sumOf { it.sizeBytes }
      for (segment in consultation.segments.filterNot { it.delivered }) {
        val outcome = try {
          sendSegment(node, auth, consultation, segment) { bytes ->
            reportProgress(consultation, sentBytes + bytes)
          }
        } catch (e: Exception) {
          logd("TransferWorker segment failed $e")
          Outcome.Retry(e.message ?: e.javaClass.simpleName)
        }
        when (outcome) {
          Outcome.Delivered -> {
            store.markSegmentDelivered(consultation.id, segment.recordingId)
            sentBytes += segment.sizeBytes
            reportProgress(consultation, sentBytes)
          }

          Outcome.Unauthorized -> {
            store.update(consultation.id) {
              it.copy(state = SyncState.FAILED, lastError = applicationContext.getString(R.string.error_unauthorized))
            }
            authStore.clear()
            return Result.success()
          }

          is Outcome.Retry -> {
            store.update(consultation.id) { it.copy(state = SyncState.QUEUED, lastError = outcome.reason) }
            return Result.retry()
          }
        }
      }
      store.markDelivered(consultation.id)
    }
    return Result.success()
  }

  private suspend fun sendSegment(
    node: Node,
    auth: WatchAuth,
    consultation: WatchConsultation,
    segment: Segment,
    onProgress: suspend (Long) -> Unit,
  ): Outcome {
    // 1. Offer
    val offer = JSONObject()
      .put("token", auth.token)
      .put("doctorPublicId", consultation.doctorPublicId)
      .put("consultationId", consultation.id)
      .put("createdAt", consultation.createdAt)
      .put("consentAt", consultation.consentAt)
      .put("segmentCount", consultation.segments.size)
      .put("segment", segment.toJson().apply { remove("fileName"); remove("delivered") })
    when (val status = rpc(node, NotlyProtocol.PATH_OFFER, offer).optString("status")) {
      NotlyProtocol.STATUS_HAVE -> return Outcome.Delivered
      NotlyProtocol.STATUS_UNAUTHORIZED -> return Outcome.Unauthorized
      NotlyProtocol.STATUS_SEND -> Unit
      else -> return Outcome.Retry("offer: $status")
    }

    // 2. Stream the file
    val file = store.segmentFile(consultation.id, segment)
    if (!file.exists()) return Outcome.Retry("missing local file")
    val channel = channelClient.openChannel(node.id, NotlyProtocol.PATH_FILE_PREFIX + segment.recordingId).await()
    try {
      withContext(Dispatchers.IO) {
        val output = channelClient.getOutputStream(channel).await()
        output.use { out ->
          file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            var lastReported = 0L
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              out.write(buffer, 0, read)
              total += read
              if (total - lastReported >= 256 * 1024) {
                lastReported = total
                onProgress(total)
              }
            }
            out.flush()
          }
        }
      }

      // 3. Verify: the phone finishes writing asynchronously after our stream closes.
      repeat(VERIFY_ATTEMPTS) {
        val verify = rpc(node, NotlyProtocol.PATH_VERIFY, JSONObject().put("recordingId", segment.recordingId))
        when (val status = verify.optString("status")) {
          NotlyProtocol.STATUS_OK -> return Outcome.Delivered
          NotlyProtocol.STATUS_PENDING -> delay(VERIFY_INTERVAL_MS)
          else -> return Outcome.Retry("verify: $status")
        }
      }
      return Outcome.Retry("verify timed out")
    } finally {
      try {
        channelClient.close(channel)
      } catch (_: Exception) {
      }
    }
  }

  private suspend fun rpc(node: Node, path: String, body: JSONObject): JSONObject {
    val bytes = withTimeout(RPC_TIMEOUT_MS) {
      messageClient.sendRequest(node.id, path, body.toString().toByteArray(Charsets.UTF_8)).await()
    }
    return JSONObject(String(bytes, Charsets.UTF_8))
  }

  private suspend fun findPhone(): Node? {
    val nodes = capabilityClient
      .getCapability(NotlyProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
    val preferred = authStore.auth.value?.phoneNodeId
    return nodes.firstOrNull { it.id == preferred } ?: nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
  }

  private suspend fun reportProgress(consultation: WatchConsultation, sentBytes: Long) {
    setProgress(
      workDataOf(
        PROGRESS_CONSULTATION_ID to consultation.id,
        PROGRESS_PERCENT to ((sentBytes * 100) / consultation.totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100),
      )
    )
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification: Notification = NotificationCompat.Builder(applicationContext, TRANSFER_NOTIFICATION_CHANNEL_ID)
      .setContentTitle(applicationContext.getString(R.string.sending_to_phone))
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
    return if (Build.VERSION.SDK_INT >= 34) {
      ForegroundInfo(TRANSFER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(TRANSFER_NOTIFICATION_ID, notification)
    }
  }

  companion object {
    const val UNIQUE_WORK = "notly_transfer"
    const val PROGRESS_CONSULTATION_ID = "consultationId"
    const val PROGRESS_PERCENT = "percent"
    private const val RPC_TIMEOUT_MS = 20_000L
    private const val VERIFY_ATTEMPTS = 20
    private const val VERIFY_INTERVAL_MS = 1_500L

    /**
     * Starts a transfer pass now. If one is already running it is left alone (it re-reads the
     * queue until empty); a pass waiting on backoff is replaced so a reconnect sends immediately.
     */
    suspend fun enqueue(context: Context) {
      val workManager = WorkManager.getInstance(context)
      val running = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).first()
        .any { it.state == WorkInfo.State.RUNNING }
      val request = OneTimeWorkRequestBuilder<TransferWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      workManager.enqueueUniqueWork(
        UNIQUE_WORK,
        if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
        request,
      )
    }
  }
}
