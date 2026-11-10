package ec.edu.espoch.omniflow.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ec.edu.espoch.omniflow.domain.model.LabSnapshot
import ec.edu.espoch.omniflow.domain.model.LabStatus
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.EspochRedDark
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.Line
import ec.edu.espoch.omniflow.ui.theme.RedSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniTopBar(
    title: String = "OmniFlow App",
    unread: Int = 0,
    onBack: (() -> Unit)? = null,
    onBell: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
            }
        },
        actions = {
            actions()
            if (onBell != null) {
                IconButton(onClick = onBell) {
                    BadgedBox(badge = { if (unread > 0) Badge(containerColor = Color.White, contentColor = EspochRed) { Text("$unread") } }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alertas")
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = EspochRed,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable
fun StatusChip(status: LabStatus, modifier: Modifier = Modifier) {
    val c = status.color()
    Text(
        status.label,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = c,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = EspochRed, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f)) }
}

@Composable
fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    sub: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    progressColor: Color = EspochRed,
    onClick: (() -> Unit)? = null,
) {
    AppCard(modifier, onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = EspochRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(value, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = progressColor,
                        trackColor = progressColor.copy(alpha = 0.15f),
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun LabHeroImage(modifier: Modifier = Modifier, height: Dp = 64.dp, icon: ImageVector = Icons.Default.Computer) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(EspochRedDark, EspochRed, Color(0xFFE85A6B)))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(height * 0.45f)) }
}

@Composable
fun LabCard(snapshot: LabSnapshot, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabHeroImage(Modifier.width(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(snapshot.lab.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snapshot.lab.location, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, null, tint = InkSoft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (snapshot.occupancy != null) "${snapshot.occupancy}/${snapshot.lab.capacity} personas"
                        else "${snapshot.lab.capacity} personas",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.hasCamera) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Videocam, "Cámara activa", tint = StatusGreen, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(snapshot.status)
                val hint = when {
                    snapshot.currentClass != null -> "Hasta ${ScheduleSlot.fmt(snapshot.currentClass.endMinute)}"
                    snapshot.status == LabStatus.PROXIMO && snapshot.minutesToNext != null -> "Inicia en ${snapshot.minutesToNext} min"
                    else -> null
                }
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, color: Color = EspochRed) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/** Grafico de barras vertical simple (porcentajes 0..100). */
@Composable
fun BarChart(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier, barColor: Color = EspochRed) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(140.dp)) {
            Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("100%", "75%", "50%", "25%", "0%").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                }
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).height(140.dp)) {
                val n = values.size.coerceAtLeast(1)
                val slot = size.width / n
                val barW = slot * 0.5f
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                values.forEachIndexed { i, v ->
                    val h = size.height * (v.coerceIn(0, 100) / 100f)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
            labels.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun PercentRow(label: String, pct: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (pct >= 70) EspochRed else if (pct >= 40) StatusAmber else StatusGreen,
            trackColor = RedSoft,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.width(8.dp))
        Text("$pct%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = InkSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
