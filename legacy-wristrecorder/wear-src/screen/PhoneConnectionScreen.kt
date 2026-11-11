package dev.tberghuis.voicememos.screen

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import dev.tberghuis.voicememos.page.launchPermissionsSettings
import dev.tberghuis.voicememos.viewmodels.PhoneConnectionState
import dev.tberghuis.voicememos.viewmodels.PhoneConnectionVm

@Composable
fun PhoneConnectionScreen(
  onConnected: () -> Unit,
  vm: PhoneConnectionVm = viewModel(),
) {
  val context = LocalContext.current
  val state by vm.state.collectAsState()

  // only auto prompt once, after that the user has to tap the button
  var permissionPrompted by rememberSaveable { mutableStateOf(false) }
  var enablePrompted by rememberSaveable { mutableStateOf(false) }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { vm.check() }
  val enableBluetoothLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { vm.check() }

  val requestPermission = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
  }
  val requestEnableBluetooth = {
    try {
      enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    } catch (e: ActivityNotFoundException) {
      openBluetoothSettings(context)
    }
  }

  // re-check whenever the user comes back from a system dialog or settings
  LifecycleResumeEffect(Unit) {
    vm.check()
    onPauseOrDispose { }
  }

  LaunchedEffect(state) {
    when (state) {
      PhoneConnectionState.Connected -> onConnected()
      PhoneConnectionState.PermissionRequired -> if (!permissionPrompted) {
        permissionPrompted = true
        requestPermission()
      }

      PhoneConnectionState.BluetoothOff -> if (!enablePrompted) {
        enablePrompted = true
        requestEnableBluetooth()
      }

      else -> {}
    }
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier
        .fillMaxWidth(0.8f)
        .fillMaxHeight(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      when (state) {
        PhoneConnectionState.Checking, PhoneConnectionState.Connected -> {
          CircularProgressIndicator()
        }

        PhoneConnectionState.PermissionRequired -> {
          Message("Allow Bluetooth permission to connect to your phone")
          ActionButton("Allow") { requestPermission() }
          ActionButton("Settings") { launchPermissionsSettings(context) }
        }

        PhoneConnectionState.BluetoothUnavailable -> {
          Message("Bluetooth is not available on this watch")
          ActionButton("Retry") { vm.check() }
        }

        PhoneConnectionState.BluetoothOff -> {
          Message("Turn on Bluetooth to connect to your phone")
          ActionButton("Turn on") { requestEnableBluetooth() }
        }

        PhoneConnectionState.NotPaired -> {
          Message("No phone paired. Pair this watch with your phone")
          ActionButton("Pair") { openBluetoothSettings(context) }
        }

        PhoneConnectionState.PairedNotConnected -> {
          Message("Phone paired but not connected. Keep your phone nearby with Bluetooth on")
          ActionButton("Retry") { vm.check() }
          ActionButton("Bluetooth") { openBluetoothSettings(context) }
        }
      }
    }
  }
}

@Composable
private fun Message(text: String) {
  Text(
    text,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(bottom = 8.dp),
  )
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 4.dp),
  ) {
    Text(label)
  }
}

private fun openBluetoothSettings(context: Context) {
  try {
    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
  } catch (e: ActivityNotFoundException) {
    context.startActivity(Intent(Settings.ACTION_SETTINGS))
  }
}
