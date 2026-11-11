package dev.tberghuis.voicememos.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import dev.tberghuis.voicememos.common.logd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface PhoneConnectionState {
  data object Checking : PhoneConnectionState
  data object PermissionRequired : PhoneConnectionState
  data object BluetoothUnavailable : PhoneConnectionState
  data object BluetoothOff : PhoneConnectionState
  data object NotPaired : PhoneConnectionState
  data object PairedNotConnected : PhoneConnectionState
  data object Connected : PhoneConnectionState
}

class PhoneConnectionVm(
  application: Application,
) : AndroidViewModel(application) {
  private val bluetoothAdapter =
    application.getSystemService(BluetoothManager::class.java)?.adapter
  private val nodeClient = Wearable.getNodeClient(application)

  val state = MutableStateFlow<PhoneConnectionState>(PhoneConnectionState.Checking)

  fun check() {
    viewModelScope.launch {
      state.value = resolveState()
      logd("PhoneConnectionVm state ${state.value}")
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun resolveState(): PhoneConnectionState {
    if (!hasBluetoothPermission()) {
      return PhoneConnectionState.PermissionRequired
    }
    // emulators have no bluetooth adapter but can still be connected to a phone via adb
    val adapter = bluetoothAdapter ?: return if (hasConnectedPhone()) {
      PhoneConnectionState.Connected
    } else {
      PhoneConnectionState.BluetoothUnavailable
    }
    if (!adapter.isEnabled) {
      return PhoneConnectionState.BluetoothOff
    }
    if (hasConnectedPhone()) {
      return PhoneConnectionState.Connected
    }
    val hasPairedPhone = adapter.bondedDevices.orEmpty().any {
      it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
    }
    return if (hasPairedPhone) {
      PhoneConnectionState.PairedNotConnected
    } else {
      PhoneConnectionState.NotPaired
    }
  }

  private fun hasBluetoothPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      // BLUETOOTH is an install time permission before Android 12
      return true
    }
    return ContextCompat.checkSelfPermission(
      getApplication(), Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
  }

  private suspend fun hasConnectedPhone(): Boolean = try {
    nodeClient.connectedNodes.await().any { it.isNearby }
  } catch (e: Exception) {
    logd("hasConnectedPhone error $e")
    false
  }
}
