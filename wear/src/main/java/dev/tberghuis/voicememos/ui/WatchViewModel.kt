package dev.tberghuis.voicememos.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.tberghuis.voicememos.common.logd
import dev.tberghuis.voicememos.data.AuthStore
import dev.tberghuis.voicememos.data.ConsultationStore
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.service.RecorderState
import dev.tberghuis.voicememos.service.RecordingService
import dev.tberghuis.voicememos.sync.NotlyProtocol
import dev.tberghuis.voicememos.sync.TransferWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class Overlay { None, Consent, PermissionDenied, ConfirmDelete, Queue }

data class TransferProgress(val consultationId: String, val percent: Int)

class WatchViewModel(application: Application) : AndroidViewModel(application) {
  private val store = ConsultationStore.get(application)
  private val capabilityClient = Wearable.getCapabilityClient(application)

  val auth = AuthStore.get(application).auth
  val consultations = store.consultations
  val recorder: StateFlow<RecorderState> = RecordingService.state

  private val _phoneReachable = MutableStateFlow(false)
  val phoneReachable: StateFlow<Boolean> = _phoneReachable

  val transferProgress: StateFlow<TransferProgress?> = WorkManager.getInstance(application)
    .getWorkInfosForUniqueWorkFlow(TransferWorker.UNIQUE_WORK)
    .map { infos ->
      infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { data ->
        data.getString(TransferWorker.PROGRESS_CONSULTATION_ID)?.let {
          TransferProgress(it, data.getInt(TransferWorker.PROGRESS_PERCENT, 0))
        }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** The consultation the watch face is focused on (summary / transfer screens). */
  var currentId by mutableStateOf(initialCurrentId())
    private set

  var overlay by mutableStateOf(Overlay.None)

  /** Short confirmation text (design "toast"). */
  var toast by mutableStateOf<String?>(null)

  // Re-query instead of trusting the event payload; events are not always delivered on reconnect.
  private val capabilityListener = CapabilityClient.OnCapabilityChangedListener {
    viewModelScope.launch { refreshPhoneReachable() }
  }

  init {
    capabilityClient.addListener(capabilityListener, NotlyProtocol.CAPABILITY_PHONE)
    viewModelScope.launch {
      while (true) {
        refreshPhoneReachable()
        delay(REACHABILITY_POLL_MS)
      }
    }
    viewModelScope.launch {
      recorder.collect { state ->
        when (state) {
          is RecorderState.Active -> currentId = state.consultationId
          is RecorderState.Failed -> state.consultationId?.let { currentId = it }
          RecorderState.Idle -> Unit
        }
      }
    }
  }

  private suspend fun refreshPhoneReachable() {
    _phoneReachable.value = try {
      capabilityClient.getCapability(NotlyProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
        .await().nodes.isNotEmpty()
    } catch (e: Exception) {
      logd("phone reachability check failed $e")
      false
    }
  }

  override fun onCleared() {
    capabilityClient.removeListener(capabilityListener)
    super.onCleared()
  }

  private fun initialCurrentId(): String? {
    (RecordingService.state.value as? RecorderState.Active)?.let { return it.consultationId }
    // Reopen an unsent recording instead of silently leaving it behind.
    return store.consultations.value.lastOrNull { it.state == SyncState.READY }?.id
  }

  fun startConsultation() {
    overlay = Overlay.Consent
  }

  fun cancelConsent() {
    overlay = Overlay.None
  }

  fun micPermissionDenied() {
    overlay = Overlay.PermissionDenied
  }

  fun confirmConsent() {
    val doctor = auth.value?.doctor ?: return
    store.purgeDelivered()
    val consultation = store.create(doctor.publicId, consentAt = System.currentTimeMillis())
    currentId = consultation.id
    overlay = Overlay.None
    RecordingService.clearFailure()
    RecordingService.start(getApplication(), consultation.id)
  }

  fun togglePause() {
    val active = recorder.value as? RecorderState.Active ?: return
    if (active.paused) RecordingService.resume(getApplication()) else RecordingService.pause(getApplication())
  }

  fun finishRecording() {
    RecordingService.finish(getApplication())
  }

  /** Consent was already confirmed for this consultation; record another segment. */
  fun continueRecording() {
    val id = currentId ?: return
    RecordingService.clearFailure()
    RecordingService.start(getApplication(), id)
  }

  fun send() {
    val id = currentId ?: return
    RecordingService.clearFailure()
    store.update(id) { it.copy(state = SyncState.QUEUED, lastError = null) }
    viewModelScope.launch { TransferWorker.enqueue(getApplication()) }
  }

  fun retry(id: String) {
    store.update(id) { it.copy(state = SyncState.QUEUED, lastError = null) }
    viewModelScope.launch { TransferWorker.enqueue(getApplication()) }
  }

  fun requestDelete() {
    overlay = Overlay.ConfirmDelete
  }

  fun confirmDelete() {
    currentId?.let { store.delete(it) }
    RecordingService.clearFailure()
    currentId = null
    overlay = Overlay.None
    toast = getApplication<Application>().getString(dev.tberghuis.voicememos.R.string.deleted)
  }

  fun cancelOverlay() {
    overlay = Overlay.None
  }

  /** Leave the transfer screen; sending continues in the background. */
  fun done() {
    currentId?.let { id -> if (store.get(id)?.state == SyncState.DELIVERED) store.delete(id) }
    currentId = null
    overlay = Overlay.None
  }

  fun openQueue() {
    overlay = Overlay.Queue
  }

  fun openConsultation(id: String) {
    currentId = id
    overlay = Overlay.None
  }

  companion object {
    private const val REACHABILITY_POLL_MS = 10_000L
  }
}
