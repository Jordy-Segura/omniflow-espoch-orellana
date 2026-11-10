package ec.edu.espoch.omniflow

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ec.edu.espoch.omniflow.update.AppUpdater
import ec.edu.espoch.omniflow.update.UpdateState
import javax.inject.Inject
import kotlinx.coroutines.launch
import ec.edu.espoch.omniflow.navigation.OmniFlowRoot
import ec.edu.espoch.omniflow.ui.theme.OmniFlowTheme
import ec.edu.espoch.omniflow.utils.NotificationHelper

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var openAlerts by mutableStateOf(false)

    @Inject lateinit var updater: AppUpdater

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openAlerts = intent?.getBooleanExtra(EXTRA_OPEN_ALERTS, false) == true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationHelper.canNotify(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Cuando Android necesita confirmar la actualizacion, mostrar su dialogo de inmediato.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updater.state.collect { st ->
                    if (st is UpdateState.NeedsConfirmation) {
                        runCatching { startActivity(st.intent) }
                        updater.confirmationShown()
                    }
                }
            }
        }

        setContent {
            OmniFlowTheme {
                Surface {
                    OmniFlowRoot(openAlerts = openAlerts, onAlertsOpened = { openAlerts = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si el usuario acaba de permitir "instalar apps desconocidas", continuar la actualizacion.
        lifecycleScope.launch { updater.resumePendingInstall() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALERTS, false)) openAlerts = true
    }

    companion object {
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}
