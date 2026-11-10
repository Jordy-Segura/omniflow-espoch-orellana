package ec.edu.espoch.omniflow.ui.screens.monitor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.vision.TrackState
import ec.edu.espoch.omniflow.vision.camera.CameraSource
import ec.edu.espoch.omniflow.vision.camera.PhoneCameraFeed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TRACK_COLORS = listOf(
    Color(0xFF00E676), Color(0xFF29B6F6), Color(0xFFFFCA28), Color(0xFFFF7043),
    Color(0xFFAB47BC), Color(0xFF26C6DA), Color(0xFFD4E157), Color(0xFFEC407A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(padding: PaddingValues, onOpenSettings: () -> Unit, vm: MonitorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val labs by vm.labs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usesPhoneCamera = settings.source != CameraSource.IP_STREAM

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) vm.start() else vm.onCameraError("Se necesita el permiso de cámara para monitorear con este teléfono")
    }

    // Mantener la pantalla encendida mientras el telefono actua como camara del laboratorio.
    val view = LocalView.current
    DisposableEffect(s.running) {
        view.keepScreenOn = s.running
        onDispose { view.keepScreenOn = false }
    }

    // Si se cambia la fuente en Ajustes mientras se monitorea, reiniciar el pipeline.
    LaunchedEffect(settings.source, settings.streamUrl, settings.useGpu, settings.attendanceMode) {
        vm.restartIfConfigChanged(settings)
    }

    if (s.running && usesPhoneCamera && hasCameraPermission) {
        PhoneCameraFeed(
            front = settings.source == CameraSource.PHONE_FRONT,
            highRes = settings.attendanceMode,
            isBusy = vm.isBusy,
            onError = vm::onCameraError,
            onFrame = vm::onPhoneFrame,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monitoreo con cámara", style = MaterialTheme.typography.titleLarge)
                Text(settings.source.label, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
            OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Fuente")
            }
        }

        // Selector de laboratorio
        var expanded by remember { mutableStateOf(false) }
        val selected = labs.firstOrNull { it.id == settings.labId }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.name ?: "Selecciona el laboratorio que vigila esta cámara",
                onValueChange = {},
                readOnly = true,
                label = { Text("Laboratorio") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                labs.forEach { lab ->
                    DropdownMenuItem(
                        text = { Text("${lab.name} (${lab.capacity})") },
                        onClick = { vm.selectLab(lab.id); expanded = false },
                    )
                }
            }
        }
        if (settings.labId == null) {
            InfoBanner("Sin laboratorio seleccionado: la detección funciona, pero no se guardarán registros ni alertas.", color = StatusAmber)
        }

        // Video + detecciones
        VideoPanel(s, settings.countingLine, settings.linePosition)

        s.streamStatus?.let { InfoBanner(it, color = StatusAmber) }
        s.error?.let { InfoBanner(it) }

        // Controles
        if (!s.running) {
            Button(
                onClick = {
                    vm.clearError()
                    if (usesPhoneCamera && !hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA) else vm.start()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar monitoreo", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = vm::stop,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A40)),
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Detener", fontWeight = FontWeight.SemiBold)
            }
        }

        // Metricas del modelo
        val r = s.result
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Personas", "${s.occupancy}", Modifier.weight(1f), highlight = true)
            Metric("Detecciones", "${r?.detections?.count { it.score >= settings.minConfidence } ?: 0}", Modifier.weight(1f))
            Metric("FPS", r?.let { "%.1f".format(it.fps) } ?: "—", Modifier.weight(1f))
            Metric("Inferencia", r?.let { "${it.inferenceMs} ms" } ?: "—", Modifier.weight(1f))
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Conteo por línea virtual (entradas/salidas)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = settings.countingLine, onCheckedChange = vm::toggleCountingLine)
                }
                if (settings.countingLine) {
                    Text(
                        "Entradas: ${r?.entries ?: 0}   ·   Salidas: ${r?.exits ?: 0}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Apunta la cámara a la puerta. Cruzar la línea hacia ${if (settings.downIsEntry) "abajo" else "arriba"} cuenta como entrada.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft,
                    )
                }
            }
        }

        AttendanceCard(s, settings.attendanceMode, vm::toggleAttendance)

        InsightCard(s)

        Text(
            "Modelo: ${s.detectorName.ifBlank { "EfficientDet-Lite0 (COCO)" }} · Seguimiento: ByteTrack (Kalman + Húngaro) · " +
                "Suavizado: mediana móvil · Registro cada ${settings.sampleSeconds} s",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
        )
    }
}

@Composable
private fun VideoPanel(s: MonitorUiState, showLine: Boolean, linePos: Float) {
    val frame = s.frame
    val ratio = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 3f / 4f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111114)),
        contentAlignment = Alignment.Center,
    ) {
        if (frame == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (s.running) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(if (s.loadingModel) "Cargando modelo de IA…" else "Esperando video…", color = Color.White)
                } else {
                    Icon(Icons.Default.VideocamOff, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Cámara detenida", color = Color.White.copy(alpha = 0.7f))
                }
            }
        } else {
            val image = remember(frame) { frame.asImageBitmap() }
            val result = s.result
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
                val w = size.width
                val h = size.height
                if (showLine) {
                    drawLine(
                        Color(0xFFFFEB3B), Offset(0f, h * linePos), Offset(w, h * linePos), strokeWidth = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f)),
                    )
                }
                val paint = android.graphics.Paint().apply {
                    textSize = 30f
                    isAntiAlias = true
                    isFakeBoldText = true
                }
                result?.tracks?.filter { it.state == TrackState.TRACKED }?.forEach { t ->
                    val c = TRACK_COLORS[t.id % TRACK_COLORS.size]
                    val left = t.box.left * w
                    val top = t.box.top * h
                    drawRect(c, Offset(left, top), Size(t.box.width * w, t.box.height * h), style = Stroke(5f))
                    val label = "#${t.id} ${(t.score * 100).toInt()}%"
                    paint.color = android.graphics.Color.BLACK
                    val tw = paint.measureText(label)
                    drawRect(c, Offset(left, (top - 38f).coerceAtLeast(0f)), Size(tw + 16f, 38f))
                    drawIntoCanvas { it.nativeCanvas.drawText(label, left + 8f, (top - 38f).coerceAtLeast(0f) + 29f, paint) }
                    drawCircle(c, 6f, Offset(t.box.cx * w, t.box.cy * h))
                }
            }
            // Chip de conteo
            Text(
                "${s.occupancy} personas",
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EspochRed)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            if (s.running) {
                Text(
                    "● EN VIVO",
                    Modifier.align(Alignment.TopEnd).padding(10.dp),
                    color = Color(0xFFFF5252),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AttendanceCard(s: MonitorUiState, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Asistencia automática por rostro", style = MaterialTheme.typography.titleSmall)
                    Text("Cada 8 s envía el fotograma al servidor OmniFlow, que reconoce a los estudiantes inscritos.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!enabled) return@Column
            s.attendanceError?.let { InfoBanner(it) }
            val a = s.attendance
            when {
                a == null -> Text("Esperando el primer fotograma con personas…", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                a.slotId == null -> Text("No hay clase programada ahora en este laboratorio: no se marca asistencia (${a.faces} rostro(s) detectados).",
                    style = MaterialTheme.typography.bodySmall, color = StatusAmber)
                else -> {
                    Text("${a.subject} · último fotograma: ${a.faces} rostro(s), ${a.recognized.size} reconocido(s), ${a.unknown} sin identificar",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (s.attendanceRecognized.isNotEmpty()) {
                        Text("Marcados en esta sesión (${s.attendanceRecognized.size}): " +
                            s.attendanceRecognized.values.joinToString(", ") { n -> n.split(" ").take(2).joinToString(" ") { w -> w.lowercase().replaceFirstChar(Char::titlecase) } },
                            style = MaterialTheme.typography.bodySmall, color = StatusGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = if (highlight) EspochRed else Color.Unspecified, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1)
        }
    }
}

@Composable
private fun InsightCard(s: MonitorUiState) {
    val insight = s.insight
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, null, tint = EspochRed)
                Spacer(Modifier.width(8.dp))
                Text("Analítica en tiempo real", style = MaterialTheme.typography.titleSmall)
            }
            if (insight == null) {
                Text(
                    "Aún no hay registros. Inicia el monitoreo con un laboratorio seleccionado.",
                    style = MaterialTheme.typography.bodySmall, color = InkSoft,
                )
                return@Column
            }
            Text(
                "Último registro: ${time.format(Date(insight.savedAt))} · ${insight.count} personas · ${s.savedSamples} muestras en esta sesión",
                style = MaterialTheme.typography.bodySmall,
            )
            val fc = insight.forecast
            Text(
                if (fc != null) "Predicción a ${fc.horizonMinutes} min: ${fc.predicted} personas (tendencia ${"%+.1f".format(fc.trendPerHour)}/h, R² ${"%.2f".format(fc.r2)})"
                else "Predicción: se necesitan al menos 4 muestras recientes.",
                style = MaterialTheme.typography.bodySmall,
            )
            insight.anomaly?.let { a ->
                Text(
                    if (a.samples < 12) "Detector de anomalías: aprendiendo (${a.samples}/12 muestras de esta franja)."
                    else "Detector de anomalías: z robusto = ${"%.2f".format(a.zScore)} (mediana ${"%.0f".format(a.median)}) → ${if (a.isAnomaly) "ANOMALÍA" else "normal"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.isAnomaly) EspochRed else StatusGreen,
                )
            }
            insight.triggers.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = if (t.type.critical) EspochRed else StatusAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${t.type.title}: ${t.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (insight.triggers.none { it.type != AlertType.PROXIMA_CLASE }) {
                Text("Reglas de negocio: sin incidencias.", style = MaterialTheme.typography.bodySmall, color = StatusGreen)
            }
        }
    }
}
