package dev.tberghuis.voicememos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import dev.tberghuis.voicememos.Constants.NOTIFICATION_CHANNEL_ID
import dev.tberghuis.voicememos.Constants.TRANSFER_NOTIFICATION_CHANNEL_ID
import dev.tberghuis.voicememos.data.ConsultationStore
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.sync.TransferWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainApplication : Application(), Configuration.Provider {
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setMinimumLoggingLevel(android.util.Log.INFO)
      .build()

  override fun onCreate() {
    super.onCreate()
    createNotificationChannels()
    val store = ConsultationStore.get(this)
    // No recorder can be running at process start: tidy up anything interrupted by a crash.
    store.recover()
    // Resume sends that were queued before the process died.
    if (store.consultations.value.any { it.state == SyncState.QUEUED || it.state == SyncState.SENDING }) {
      appScope.launch { TransferWorker.enqueue(this@MainApplication) }
    }
  }

  private fun createNotificationChannels() {
    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
      NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.recording), NotificationManager.IMPORTANCE_DEFAULT)
    )
    notificationManager.createNotificationChannel(
      NotificationChannel(TRANSFER_NOTIFICATION_CHANNEL_ID, getString(R.string.channel_transfer), NotificationManager.IMPORTANCE_LOW)
    )
  }
}
