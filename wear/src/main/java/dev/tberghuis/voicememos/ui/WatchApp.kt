package dev.tberghuis.voicememos.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.tberghuis.voicememos.R
import dev.tberghuis.voicememos.data.DoctorProfile
import dev.tberghuis.voicememos.data.SyncState
import dev.tberghuis.voicememos.data.WatchConsultation
import dev.tberghuis.voicememos.service.RecorderState
import kotlinx.coroutines.delay

@Composable
fun WatchApp(vm: WatchViewModel = viewModel()) {
  val context = LocalContext.current
  val auth by vm.auth.collectAsState()
  val consultations by vm.consultations.collectAsState()
  val recorder by vm.recorder.collectAsState()
  val reachable by vm.phoneReachable.collectAsState()
  val progress by vm.transferProgress.collectAsState()
  val current = consultations.firstOrNull { it.id == vm.currentId }
  val pending = consultations.filter {
    it.state == SyncState.QUEUED || it.state == SyncState.SENDING || it.state == SyncState.FAILED
  }

  // Microphone (+ notifications on 13+) are requested when the doctor starts a consultation.
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startConsultation() else vm.micPermissionDenied()
  }
  val requestStart = {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) {
      vm.startConsultation()
    } else {
      permissionLauncher.launch(
        if (Build.VERSION.SDK_INT >= 33) {
          arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
          arrayOf(Manifest.permission.RECORD_AUDIO)
        }
      )
    }
  }

  vm.toast?.let { text ->
    LaunchedEffect(text) {
      Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
      vm.toast = null
    }
  }

  MaterialTheme {
    val rec = recorder
    when {
      rec is RecorderState.Active -> RecordingScreen(rec, reachable, vm)

      vm.overlay == Overlay.PermissionDenied -> PermissionScreen(
        reachable,
        onSettings = {
          context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
          )
        },
        onRetry = { vm.cancelOverlay(); requestStart() },
      )

      vm.overlay == Overlay.Queue -> QueueScreen(pending, reachable, vm)

      auth == null && current == null -> SetupScreen(reachable, pending.size, vm)

      vm.overlay == Overlay.Consent -> ConsentScreen(reachable, vm)

      current != null && vm.overlay == Overlay.ConfirmDelete -> ConfirmDeleteScreen(reachable, vm)

      current != null -> when (current.state) {
        SyncState.RECORDING, SyncState.READY -> SummaryScreen(current, reachable, auth != null, rec, vm)
        SyncState.QUEUED, SyncState.SENDING -> {
          val percent = progress?.takeIf { it.consultationId == current.id }?.percent
          if (!reachable && percent == null) QueuedScreen(current, reachable, vm)
          else SendingScreen(percent ?: 0, reachable, vm)
        }

        SyncState.DELIVERED -> DeliveredScreen(reachable, vm, requestStart)
        SyncState.FAILED -> FailedScreen(current, reachable, vm)
      }

      else -> ReadyScreen(auth!!.doctor, reachable, pending.size, vm, requestStart)
    }
  }
}

/** Scrollable round-screen page with the design's status line, body and footer. */
@Composable
private fun WatchPage(reachable: Boolean, content: ScalingLazyListScope.() -> Unit) {
  val listState = rememberScalingLazyListState(initialCenterItemIndex = 2)
  val focusRequester = remember { FocusRequester() }
  Scaffold(
    modifier = Modifier.background(NotlyColors.Background),
    timeText = { TimeText() },
    positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
  ) {
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .background(NotlyColors.Background)
        .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = listState), focusRequester)
        .focusRequester(focusRequester)
        .focusable(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 30.dp, bottom = 40.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item { ConnectionStatus(reachable) }
      content()
    }
  }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun SetupScreen(reachable: Boolean, pendingCount: Int, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { Logo() }
    item { Kicker(stringResource(R.string.kicker_companion)) }
    item { Title(stringResource(R.string.setup_title)) }
    item { Copy(stringResource(if (reachable) R.string.setup_copy else R.string.setup_offline_copy)) }
    if (pendingCount > 0) {
      item { WatchButton(pluralStringResource(R.plurals.waiting_to_send, pendingCount, pendingCount), vm::openQueue) }
    }
  }
}

@Composable
private fun ReadyScreen(
  doctor: DoctorProfile,
  reachable: Boolean,
  pendingCount: Int,
  vm: WatchViewModel,
  requestStart: () -> Unit,
) {
  WatchPage(reachable) {
    item { Logo() }
    item { Kicker(stringResource(R.string.kicker_ready)) }
    item { Title(stringResource(R.string.ready_title)) }
    item { Copy(stringResource(R.string.ready_copy)) }
    item { UserCard(doctor) }
    item { WatchButton(stringResource(R.string.start_consultation), requestStart, style = ButtonStyle.Primary) }
    if (pendingCount > 0) {
      item { WatchButton(pluralStringResource(R.plurals.waiting_to_send, pendingCount, pendingCount), vm::openQueue) }
    }
  }
}

@Composable
private fun ConsentScreen(reachable: Boolean, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.Check, NotlyColors.BlueTint, NotlyColors.Blue.copy(alpha = 0.16f)) }
    item { Kicker(stringResource(R.string.kicker_before_recording)) }
    item { Title(stringResource(R.string.consent_title)) }
    item { Copy(stringResource(R.string.consent_copy)) }
    item { Note(stringResource(R.string.consent_note)) }
    item {
      TwoButtons(
        stringResource(R.string.cancel), vm::cancelConsent,
        stringResource(R.string.consent_confirmed), vm::confirmConsent,
        ButtonStyle.Primary,
      )
    }
  }
}

@Composable
private fun PermissionScreen(reachable: Boolean, onSettings: () -> Unit, onRetry: () -> Unit) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.MicOff, NotlyColors.DangerText, NotlyColors.DangerBg) }
    item { Title(stringResource(R.string.mic_needed_title)) }
    item { Copy(stringResource(R.string.mic_needed_copy)) }
    item { WatchButton(stringResource(R.string.try_again), onRetry, style = ButtonStyle.Primary) }
    item { WatchButton(stringResource(R.string.open_settings), onSettings) }
  }
}

@Composable
private fun RecordingScreen(state: RecorderState.Active, reachable: Boolean, vm: WatchViewModel) {
  var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  LaunchedEffect(state) {
    while (true) {
      now = SystemClock.elapsedRealtime()
      delay(250)
    }
  }
  WatchPage(reachable) {
    item {
      Kicker(
        stringResource(if (state.paused) R.string.paused_label else R.string.recording_label),
        color = NotlyColors.RedLabel,
      )
    }
    item {
      Text(
        formatDuration(state.elapsedMs(now)),
        color = Color.White,
        fontSize = 34.sp,
        fontWeight = FontWeight.Normal,
      )
    }
    item { Wave(active = !state.paused) }
    item { RecordOrb(state.paused) }
    item {
      if (state.micSilenced) Note(stringResource(R.string.mic_silenced), NotlyColors.Amber)
      else Copy(stringResource(if (reachable) R.string.recording_phone_connected else R.string.recording_offline))
    }
    item {
      TwoButtons(
        stringResource(if (state.paused) R.string.resume else R.string.pause), vm::togglePause,
        stringResource(R.string.finish), vm::finishRecording,
        ButtonStyle.Danger,
      )
    }
  }
}

@Composable
private fun SummaryScreen(
  consultation: WatchConsultation,
  reachable: Boolean,
  authorised: Boolean,
  recorder: RecorderState,
  vm: WatchViewModel,
) {
  val hasAudio = consultation.segments.isNotEmpty()
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.Check, NotlyColors.Green, NotlyColors.GreenSoft, shapeRadius = 36, size = 64) }
    item { Kicker(stringResource(R.string.kicker_recorded)) }
    item { Title(stringResource(R.string.summary_title)) }
    item { Copy(stringResource(R.string.summary_copy)) }
    item { DurationCard(stringResource(R.string.duration), formatDuration(consultation.durationMs)) }
    val error = (recorder as? RecorderState.Failed)?.message ?: consultation.lastError
    error?.let { item { Note(it, NotlyColors.Amber) } }
    if (!authorised) item { Note(stringResource(R.string.not_authorised_send), NotlyColors.Amber) }
    item {
      WatchButton(
        stringResource(if (reachable) R.string.send_to_phone else R.string.queue_for_phone),
        vm::send,
        style = ButtonStyle.Primary,
        enabled = hasAudio && authorised,
      )
    }
    if (authorised) item { WatchButton(stringResource(R.string.continue_recording), vm::continueRecording) }
    item { WatchButton(stringResource(R.string.delete), vm::requestDelete, style = ButtonStyle.Danger) }
  }
}

@Composable
private fun ConfirmDeleteScreen(reachable: Boolean, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.ErrorOutline, NotlyColors.DangerText, NotlyColors.DangerBg) }
    item { Title(stringResource(R.string.delete_title)) }
    item { Copy(stringResource(R.string.delete_copy)) }
    item {
      TwoButtons(
        stringResource(R.string.cancel), vm::cancelOverlay,
        stringResource(R.string.delete), vm::confirmDelete,
        ButtonStyle.Danger,
      )
    }
  }
}

@Composable
private fun QueuedScreen(consultation: WatchConsultation, reachable: Boolean, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.HourglassTop, NotlyColors.Amber, NotlyColors.Amber.copy(alpha = 0.15f), shapeRadius = 24, size = 64) }
    item { Kicker(stringResource(R.string.kicker_queued)) }
    item { Title(stringResource(R.string.queued_title)) }
    item { Copy(stringResource(R.string.queued_copy)) }
    item { DurationCard(stringResource(R.string.duration), formatDuration(consultation.durationMs)) }
    item { WatchButton(stringResource(R.string.done), vm::done) }
  }
}

@Composable
private fun SendingScreen(percent: Int, reachable: Boolean, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { ProgressRing(percent) }
    item { Kicker(stringResource(R.string.kicker_in_progress)) }
    item { Title(stringResource(R.string.sending_title)) }
    item { Copy(stringResource(R.string.sending_copy)) }
    item { WatchButton(stringResource(R.string.done), vm::done) }
  }
}

@Composable
private fun DeliveredScreen(reachable: Boolean, vm: WatchViewModel, requestStart: () -> Unit) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.Check, NotlyColors.Green, NotlyColors.GreenSoft, shapeRadius = 36, size = 64) }
    item { Kicker(stringResource(R.string.kicker_delivered), color = NotlyColors.Live) }
    item { Title(stringResource(R.string.delivered_title)) }
    item { Copy(stringResource(R.string.delivered_copy)) }
    item {
      WatchButton(
        stringResource(R.string.new_consultation),
        { vm.done(); requestStart() },
        style = ButtonStyle.Primary,
      )
    }
    item { WatchButton(stringResource(R.string.done), vm::done) }
  }
}

@Composable
private fun FailedScreen(consultation: WatchConsultation, reachable: Boolean, vm: WatchViewModel) {
  WatchPage(reachable) {
    item { IconBadge(Icons.Rounded.ErrorOutline, NotlyColors.Amber, NotlyColors.Amber.copy(alpha = 0.15f)) }
    item { Kicker(stringResource(R.string.kicker_failed)) }
    item { Title(stringResource(R.string.failed_title)) }
    consultation.lastError?.let { item { Copy(it) } }
    item { DurationCard(stringResource(R.string.duration), formatDuration(consultation.durationMs)) }
    item { WatchButton(stringResource(R.string.retry), { vm.retry(consultation.id) }, style = ButtonStyle.Primary) }
    item { WatchButton(stringResource(R.string.delete), vm::requestDelete, style = ButtonStyle.Danger) }
    item { WatchButton(stringResource(R.string.done), vm::done) }
  }
}

@Composable
private fun QueueScreen(pending: List<WatchConsultation>, reachable: Boolean, vm: WatchViewModel) {
  val context = LocalContext.current
  WatchPage(reachable) {
    item { Title(stringResource(R.string.queue_title)) }
    if (pending.isEmpty()) item { Copy(stringResource(R.string.queue_empty)) }
    pending.forEach { c ->
      item(key = c.id) {
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NotlyColors.Surface)
            .clickable { vm.openConsultation(c.id) }
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              DateFormat.getTimeFormat(context).format(c.createdAt),
              color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            Text(formatDuration(c.durationMs), color = NotlyColors.Kicker, fontSize = 10.sp)
          }
          Box(Modifier.padding(start = 6.dp)) {
            Text(
              c.state.name.lowercase().replaceFirstChar { it.uppercase() },
              color = if (c.state == SyncState.FAILED) NotlyColors.Amber else NotlyColors.BlueTint,
              fontSize = 10.sp,
            )
          }
        }
      }
    }
    item { WatchButton(stringResource(R.string.back), vm::cancelOverlay) }
  }
}
