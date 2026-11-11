package dev.tberghuis.voicememos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import dev.tberghuis.voicememos.R
import dev.tberghuis.voicememos.data.DoctorProfile

/** Colours from WearOS.html (:root and .watch-* rules). */
object NotlyColors {
  val Background = Color(0xFF080A0E)
  val Blue = Color(0xFF087CF0)
  val Blue2 = Color(0xFF4AA9FF)
  val BlueTint = Color(0xFF65B6FF)
  val Green = Color(0xFF16A269)
  val GreenSoft = Color(0xFFE9F8F1)
  val Red = Color(0xFFF04438)
  val RedLabel = Color(0xFFFF8B84)
  val DangerText = Color(0xFFFF8178)
  val DangerBg = Color(0xFF24191B)
  val Amber = Color(0xFFFFB64D)
  val Surface = Color(0xFF151820)
  val Button = Color(0xFF20242D)
  val Kicker = Color(0xFF8D94A3)
  val Copy = Color(0xFFAEB4C0)
  val Status = Color(0xFFB9BDC7)
  val Live = Color(0xFF7DDBAE)
  val Track = Color(0xFF1C212A)
  val Wave = Color(0xFF7BC4FF)
}

enum class ButtonStyle { Primary, Secondary, Danger, Success }

@Composable
fun ConnectionStatus(connected: Boolean) {
  Text(
    text = stringResource(if (connected) R.string.status_connected else R.string.status_offline),
    color = if (connected) NotlyColors.Live else NotlyColors.Status,
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
  )
}

@Composable
fun Logo() {
  Box(
    Modifier
      .size(46.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(Brush.linearGradient(listOf(NotlyColors.Blue, NotlyColors.Blue2))),
    contentAlignment = Alignment.Center,
  ) {
    Text("N", fontSize = 21.sp, fontWeight = FontWeight.Black, color = Color.White)
  }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, background: Color, shapeRadius: Int = 20, size: Int = 56) {
  Box(
    Modifier
      .size(size.dp)
      .clip(RoundedCornerShape(shapeRadius.dp))
      .background(background),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
  }
}

@Composable
fun Kicker(text: String, color: Color = NotlyColors.Kicker) {
  Text(
    text = text.uppercase(),
    color = color,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.9.sp,
    textAlign = TextAlign.Center,
  )
}

@Composable
fun Title(text: String) {
  Text(
    text = text,
    color = Color.White,
    fontSize = 18.sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
    lineHeight = 21.sp,
  )
}

@Composable
fun Copy(text: String, color: Color = NotlyColors.Copy) {
  Text(
    text = text,
    color = color,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    textAlign = TextAlign.Center,
  )
}

@Composable
fun Note(text: String, color: Color = NotlyColors.Kicker) {
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(NotlyColors.Surface)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
  }
}

@Composable
fun UserCard(doctor: DoctorProfile) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(NotlyColors.Surface)
      .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(Brush.linearGradient(listOf(Color(0xFFDBEEFF), Color(0xFF8EC7FF)))),
      contentAlignment = Alignment.Center,
    ) {
      Text(doctor.initials, color = Color(0xFF0B4E89), fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
    Spacer(Modifier.width(9.dp))
    Column {
      Text(doctor.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      (doctor.clinic ?: doctor.specialty)?.let {
        Text(it, color = NotlyColors.Kicker, fontSize = 10.sp, maxLines = 1)
      }
    }
  }
}

@Composable
fun DurationCard(label: String, value: String) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(NotlyColors.Surface)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, color = NotlyColors.Kicker, fontSize = 11.sp)
    Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
fun WatchButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: ButtonStyle = ButtonStyle.Secondary,
  enabled: Boolean = true,
) {
  val (bg, fg) = when (style) {
    ButtonStyle.Primary -> NotlyColors.Blue to Color.White
    ButtonStyle.Secondary -> NotlyColors.Button to Color.White
    ButtonStyle.Danger -> NotlyColors.DangerBg to NotlyColors.DangerText
    ButtonStyle.Success -> NotlyColors.Green to Color.White
  }
  Box(
    modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(bg)
      .alpha(if (enabled) 1f else 0.45f)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 9.dp, vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
  }
}

@Composable
fun TwoButtons(
  left: String,
  onLeft: () -> Unit,
  right: String,
  onRight: () -> Unit,
  rightStyle: ButtonStyle,
) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    WatchButton(left, onLeft, Modifier.weight(1f))
    WatchButton(right, onRight, Modifier.weight(1f), style = rightStyle)
  }
}

@Composable
fun ProgressRing(percent: Int) {
  Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(
      progress = percent / 100f,
      modifier = Modifier.size(84.dp),
      indicatorColor = NotlyColors.Blue,
      trackColor = NotlyColors.Track,
      strokeWidth = 7.dp,
    )
    Text("$percent%", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
  }
}

@Composable
fun RecordOrb(paused: Boolean) {
  Box(
    Modifier
      .size(64.dp)
      .clip(CircleShape)
      .background(NotlyColors.Red.copy(alpha = 0.14f))
      .border(2.dp, NotlyColors.Red.copy(alpha = 0.24f), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .size(if (paused) 40.dp else 30.dp)
        .clip(if (paused) CircleShape else RoundedCornerShape(9.dp))
        .background(NotlyColors.Red),
    )
  }
}

@Composable
fun Wave(active: Boolean) {
  val transition = rememberInfiniteTransition(label = "wave")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
    label = "phase",
  )
  Row(
    Modifier.height(28.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(14) { i ->
      val offset = listOf(0f, 0.4f, 0.8f)[i % 3]
      val level = if (active) ((phase + offset) % 1f) else 0f
      Box(
        Modifier
          .width(3.dp)
          .height((7 + level * 21).dp)
          .clip(RoundedCornerShape(6.dp))
          .background(NotlyColors.Wave.copy(alpha = if (active) 0.85f else 0.48f)),
      )
    }
  }
}

fun formatDuration(ms: Long): String {
  val totalSeconds = ms / 1000
  return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
