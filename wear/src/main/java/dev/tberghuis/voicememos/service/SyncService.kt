package dev.tberghuis.voicememos.service

import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService
import dev.tberghuis.voicememos.common.logd
import dev.tberghuis.voicememos.data.AuthStore
import dev.tberghuis.voicememos.data.ConsultationStore
import dev.tberghuis.voicememos.data.DoctorProfile
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.data.WatchAuth
import dev.tberghuis.voicememos.sync.NotlyProtocol
import dev.tberghuis.voicememos.sync.TransferWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Data Layer entry point on the watch. Runs even when the UI is closed.
 *  - RPC from the phone: authorize / revoke / status
 *  - CAPABILITY_CHANGED for the phone: resume queued transfers when the phone becomes reachable
 */
class SyncService : WearableListenerService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val authStore by lazy { AuthStore.get(this) }
  private val store by lazy { ConsultationStore.get(this) }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
    val response = try {
      val body = if (request.isEmpty()) JSONObject() else JSONObject(String(request, Charsets.UTF_8))
      when (path) {
        NotlyProtocol.PATH_AUTHORIZE -> authorize(nodeId, body)
        NotlyProtocol.PATH_REVOKE -> revoke()
        NotlyProtocol.PATH_STATUS -> status()
        else -> return null
      }
    } catch (e: Exception) {
      logd("SyncService onRequest $path failed $e")
      JSONObject().put("status", NotlyProtocol.STATUS_ERROR).put("message", e.message)
    }
    return Tasks.forResult(response.toString().toByteArray(Charsets.UTF_8))
  }

  private fun authorize(nodeId: String, body: JSONObject): JSONObject {
    val doctor = DoctorProfile.fromJson(body.getJSONObject("doctor"))
    authStore.save(
      WatchAuth(
        token = body.getString("token"),
        doctor = doctor,
        phoneNodeId = nodeId,
        authorizedAt = System.currentTimeMillis(),
      )
    )
    // Recordings that were rejected for a stale credential can now be retried.
    store.consultations.value
      .filter { it.state == SyncState.FAILED && it.doctorPublicId == doctor.publicId }
      .forEach { c -> store.update(c.id) { it.copy(state = SyncState.QUEUED, lastError = null) } }
    scope.launch { TransferWorker.enqueue(applicationContext) }
    return JSONObject()
      .put("status", NotlyProtocol.STATUS_OK)
      .put("deviceName", Build.MODEL)
  }

  private fun revoke(): JSONObject {
    // Queued recordings are kept; they will send again once the watch is re-authorised.
    authStore.clear()
    return JSONObject().put("status", NotlyProtocol.STATUS_OK)
  }

  private fun status(): JSONObject {
    val auth = authStore.auth.value
    val consultations = store.consultations.value
    return JSONObject()
      .put("status", NotlyProtocol.STATUS_OK)
      .put("authorized", auth != null)
      .put("doctorPublicId", auth?.doctor?.publicId)
      .put("deviceName", Build.MODEL)
      .put("recording", RecordingService.state.value is RecorderState.Active)
      .put("pendingCount", consultations.count {
        it.state == SyncState.QUEUED || it.state == SyncState.SENDING || it.state == SyncState.FAILED
      })
  }

  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    super.onCapabilityChanged(capabilityInfo)
    if (capabilityInfo.name == NotlyProtocol.CAPABILITY_PHONE && capabilityInfo.nodes.isNotEmpty()) {
      logd("phone reachable, resuming transfers")
      scope.launch { TransferWorker.enqueue(applicationContext) }
    }
  }
}
