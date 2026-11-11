package dev.tberghuis.voicememos.data

import android.content.Context
import dev.tberghuis.voicememos.common.logd
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class SyncState {
  /** Microphone capture in progress (or interrupted by a crash, see [ConsultationStore.recover]). */
  RECORDING,

  /** Finished, waiting for the doctor to tap Send / Continue / Delete. */
  READY,

  /** Doctor tapped Send; waiting for the phone. */
  QUEUED,
  SENDING,

  /** Phone verified every segment. Audio has been deleted from the watch. */
  DELIVERED,

  /** Needs user action (e.g. watch no longer authorised). See lastError. */
  FAILED,
}

/** One finalized .m4a file. A consultation has several when "Continue recording" is used. */
data class Segment(
  val index: Int,
  val recordingId: String,
  val fileName: String,
  val durationMs: Long,
  val sizeBytes: Long,
  val sha256: String,
  val delivered: Boolean = false,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("index", index)
    .put("recordingId", recordingId)
    .put("fileName", fileName)
    .put("durationMs", durationMs)
    .put("sizeBytes", sizeBytes)
    .put("sha256", sha256)
    .put("delivered", delivered)

  companion object {
    fun fromJson(j: JSONObject) = Segment(
      index = j.getInt("index"),
      recordingId = j.getString("recordingId"),
      fileName = j.getString("fileName"),
      durationMs = j.getLong("durationMs"),
      sizeBytes = j.getLong("sizeBytes"),
      sha256 = j.getString("sha256"),
      delivered = j.optBoolean("delivered", false),
    )
  }
}

data class WatchConsultation(
  val id: String,
  val doctorPublicId: String,
  val createdAt: Long,
  val consentAt: Long,
  val segments: List<Segment>,
  val state: SyncState,
  val lastError: String? = null,
  val updatedAt: Long = System.currentTimeMillis(),
) {
  val durationMs: Long get() = segments.sumOf { it.durationMs }
  val totalBytes: Long get() = segments.sumOf { it.sizeBytes }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("doctorPublicId", doctorPublicId)
    .put("createdAt", createdAt)
    .put("consentAt", consentAt)
    .put("segments", JSONArray().apply { segments.forEach { put(it.toJson()) } })
    .put("state", state.name)
    .put("lastError", lastError)
    .put("updatedAt", updatedAt)

  companion object {
    fun fromJson(j: JSONObject) = WatchConsultation(
      id = j.getString("id"),
      doctorPublicId = j.getString("doctorPublicId"),
      createdAt = j.getLong("createdAt"),
      consentAt = j.getLong("consentAt"),
      segments = j.getJSONArray("segments").let { arr ->
        (0 until arr.length()).map { Segment.fromJson(arr.getJSONObject(it)) }
      },
      state = SyncState.valueOf(j.getString("state")),
      lastError = if (j.isNull("lastError")) null else j.getString("lastError"),
      updatedAt = j.optLong("updatedAt"),
    )
  }
}

/**
 * Persists consultations under filesDir/consultations/<id>/ :
 *   consultation.json  - metadata + sync state (written atomically)
 *   <recordingId>.m4a  - finalized audio segments
 *
 * App-private storage (not backed up, see manifest). Audio is only deleted once the phone has
 * verified it ([markDelivered]) or the doctor explicitly deletes the consultation.
 */
class ConsultationStore private constructor(context: Context) {
  private val root = File(context.filesDir, "consultations").apply { mkdirs() }
  private val _consultations = MutableStateFlow(loadAll())
  val consultations: StateFlow<List<WatchConsultation>> = _consultations.asStateFlow()

  fun dir(id: String) = File(root, id)
  fun segmentFile(id: String, segment: Segment) = File(dir(id), segment.fileName)

  @Synchronized
  fun get(id: String): WatchConsultation? = _consultations.value.firstOrNull { it.id == id }

  @Synchronized
  fun create(doctorPublicId: String, consentAt: Long): WatchConsultation {
    val c = WatchConsultation(
      id = UUID.randomUUID().toString(),
      doctorPublicId = doctorPublicId,
      createdAt = System.currentTimeMillis(),
      consentAt = consentAt,
      segments = emptyList(),
      state = SyncState.RECORDING,
    )
    dir(c.id).mkdirs()
    write(c)
    return c
  }

  /** Returns a fresh file for the next segment of [consultationId]. */
  fun newSegmentFile(consultationId: String, recordingId: String): File =
    File(dir(consultationId).apply { mkdirs() }, "$recordingId.m4a")

  @Synchronized
  fun addSegment(consultationId: String, recordingId: String, file: File, durationMs: Long) {
    val c = get(consultationId) ?: return
    val segment = Segment(
      index = c.segments.size,
      recordingId = recordingId,
      fileName = file.name,
      durationMs = durationMs,
      sizeBytes = file.length(),
      sha256 = sha256(file),
    )
    write(c.copy(segments = c.segments + segment))
  }

  @Synchronized
  fun update(id: String, transform: (WatchConsultation) -> WatchConsultation): WatchConsultation? {
    val c = get(id) ?: return null
    val updated = transform(c).copy(updatedAt = System.currentTimeMillis())
    write(updated)
    return updated
  }

  @Synchronized
  fun markSegmentDelivered(id: String, recordingId: String) {
    update(id) { c ->
      c.copy(segments = c.segments.map { if (it.recordingId == recordingId) it.copy(delivered = true) else it })
    }
  }

  /** Phone confirmed every segment: drop the audio, keep a small receipt for the UI. */
  @Synchronized
  fun markDelivered(id: String) {
    val c = get(id) ?: return
    c.segments.forEach { segmentFile(id, it).delete() }
    update(id) { it.copy(state = SyncState.DELIVERED, lastError = null) }
  }

  @Synchronized
  fun delete(id: String) {
    dir(id).deleteRecursively()
    _consultations.value = _consultations.value.filterNot { it.id == id }
  }

  /** Consultations the transfer worker should (re)send, oldest first. */
  @Synchronized
  fun nextToSend(): WatchConsultation? = _consultations.value
    .filter { it.state == SyncState.QUEUED || it.state == SyncState.SENDING }
    .minByOrNull { it.createdAt }

  /** Delivered receipts are only needed for the confirmation screen. */
  @Synchronized
  fun purgeDelivered() {
    _consultations.value.filter { it.state == SyncState.DELIVERED }.forEach { delete(it.id) }
  }

  /**
   * Called at process start, when no recorder can be running (the recording service lives in this
   * process). A consultation still marked RECORDING was interrupted: keep its finalized segments
   * and remove any audio file that never got finalized (an MPEG-4 file without its index is
   * unplayable).
   */
  @Synchronized
  fun recover() {
    _consultations.value.filter { it.state == SyncState.RECORDING }.forEach { c ->
      val known = c.segments.map { it.fileName }.toSet()
      dir(c.id).listFiles()?.filter { it.extension == "m4a" && it.name !in known }?.forEach {
        logd("recover: discarding unfinalized segment ${it.name}")
        it.delete()
      }
      if (c.segments.isEmpty()) {
        delete(c.id)
      } else {
        update(c.id) { it.copy(state = SyncState.READY, lastError = "Recording was interrupted") }
      }
    }
  }

  private fun write(c: WatchConsultation) {
    val dir = dir(c.id).apply { mkdirs() }
    val tmp = File(dir, "consultation.json.tmp")
    tmp.writeText(c.toJson().toString())
    tmp.renameTo(File(dir, "consultation.json"))
    _consultations.value = (_consultations.value.filterNot { it.id == c.id } + c).sortedBy { it.createdAt }
  }

  private fun loadAll(): List<WatchConsultation> =
    root.listFiles()?.mapNotNull { dir ->
      val f = File(dir, "consultation.json")
      if (!f.exists()) return@mapNotNull null
      try {
        WatchConsultation.fromJson(JSONObject(f.readText()))
      } catch (e: Exception) {
        logd("ConsultationStore: unreadable ${dir.name} $e")
        null
      }
    }.orEmpty().sortedBy { it.createdAt }

  companion object {
    @Volatile
    private var instance: ConsultationStore? = null

    fun get(context: Context): ConsultationStore = instance ?: synchronized(this) {
      instance ?: ConsultationStore(context.applicationContext).also { instance = it }
    }

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
