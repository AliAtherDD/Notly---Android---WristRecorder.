package dev.tberghuis.voicememos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class DoctorProfile(
  val publicId: String,
  val name: String,
  val clinic: String?,
  val specialty: String?,
) {
  val initials: String
    get() = name.split(" ").filter { it.isNotBlank() }.take(2)
      .joinToString("") { it.first().uppercase() }.ifEmpty { "DR" }

  companion object {
    fun fromJson(json: JSONObject) = DoctorProfile(
      publicId = json.getString("publicId"),
      name = json.getString("name"),
      clinic = json.optString("clinic").ifBlank { null },
      specialty = json.optString("specialty").ifBlank { null },
    )
  }
}

/**
 * Limited companion credential issued by the phone on "Authorise Watch".
 * The doctor's Notly password / API token never reaches the watch.
 */
data class WatchAuth(
  val token: String,
  val doctor: DoctorProfile,
  val phoneNodeId: String,
  val authorizedAt: Long,
)

class AuthStore private constructor(context: Context) {
  private val prefs = context.getSharedPreferences("notly_auth", Context.MODE_PRIVATE)
  private val _auth = MutableStateFlow(read())
  val auth: StateFlow<WatchAuth?> = _auth.asStateFlow()

  @Synchronized
  fun save(auth: WatchAuth) {
    prefs.edit()
      .putString(KEY_TOKEN, auth.token)
      .putString(KEY_PUBLIC_ID, auth.doctor.publicId)
      .putString(KEY_NAME, auth.doctor.name)
      .putString(KEY_CLINIC, auth.doctor.clinic)
      .putString(KEY_SPECIALTY, auth.doctor.specialty)
      .putString(KEY_PHONE_NODE, auth.phoneNodeId)
      .putLong(KEY_AUTHORIZED_AT, auth.authorizedAt)
      .commit()
    _auth.value = auth
  }

  @Synchronized
  fun clear() {
    prefs.edit().clear().commit()
    _auth.value = null
  }

  private fun read(): WatchAuth? {
    val token = prefs.getString(KEY_TOKEN, null) ?: return null
    val publicId = prefs.getString(KEY_PUBLIC_ID, null) ?: return null
    return WatchAuth(
      token = token,
      doctor = DoctorProfile(
        publicId = publicId,
        name = prefs.getString(KEY_NAME, null).orEmpty(),
        clinic = prefs.getString(KEY_CLINIC, null),
        specialty = prefs.getString(KEY_SPECIALTY, null),
      ),
      phoneNodeId = prefs.getString(KEY_PHONE_NODE, null).orEmpty(),
      authorizedAt = prefs.getLong(KEY_AUTHORIZED_AT, 0L),
    )
  }

  companion object {
    private const val KEY_TOKEN = "token"
    private const val KEY_PUBLIC_ID = "doctor_public_id"
    private const val KEY_NAME = "doctor_name"
    private const val KEY_CLINIC = "doctor_clinic"
    private const val KEY_SPECIALTY = "doctor_specialty"
    private const val KEY_PHONE_NODE = "phone_node_id"
    private const val KEY_AUTHORIZED_AT = "authorized_at"

    @Volatile
    private var instance: AuthStore? = null

    fun get(context: Context): AuthStore = instance ?: synchronized(this) {
      instance ?: AuthStore(context.applicationContext).also { instance = it }
    }
  }
}
