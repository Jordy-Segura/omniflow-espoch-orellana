package ec.edu.espoch.omniflow.ui.screens.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ec.edu.espoch.omniflow.domain.model.Role
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.RedSoft

@Composable
fun OmniFlowLogo(modifier: Modifier = Modifier, color: Color = EspochRed) {
    Canvas(modifier.size(84.dp)) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(color, -90f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color.copy(alpha = 0.45f), 180f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawCircle(color, radius = size.minDimension * 0.13f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            OmniFlowLogo()
            Spacer(Modifier.height(12.dp))
            Text("OmniFlow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("App", style = MaterialTheme.typography.titleLarge, color = EspochRed)
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = s.username,
                onValueChange = vm::onUsername,
                label = { Text("Usuario institucional") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = s.password,
                onValueChange = vm::onPassword,
                label = { Text("Contraseña") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar contraseña")
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = vm::login,
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (s.loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Ingresar", fontWeight = FontWeight.SemiBold)
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                InfoBanner(it)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = vm::toggleDemo) {
                Text(if (s.showDemo) "Ocultar modo demostración" else "Entrar en modo demostración", color = EspochRed)
            }

            if (s.showDemo) {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth().background(RedSoft.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Modo demostración", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s.oasisAvailable) "Prueba la app sin usar tu cuenta institucional."
                        else "Esta compilación aún no tiene las credenciales del servicio OASIS (las entrega la DTIC). " +
                            "Elige un rol para probar la app; la cámara y el modelo de IA funcionan igual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    OutlinedTextField(
                        value = s.demoName,
                        onValueChange = vm::onDemoName,
                        label = { Text("Tu nombre (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Role.entries.forEach { r ->
                            FilterChip(
                                selected = s.demoRole == r,
                                onClick = { vm.onDemoRole(r) },
                                label = { Text(r.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EspochRed,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                    OutlinedButton(onClick = vm::loginDemo, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Entrar como ${s.demoRole.label}")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Text("ESPOCH Sede Orellana", style = MaterialTheme.typography.titleSmall, color = EspochRed)
            Text(
                "Sistema de monitoreo de laboratorios",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}
