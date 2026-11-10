package ec.edu.espoch.omniflow.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AlertRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.data.repository.DemoDataSeeder
import ec.edu.espoch.omniflow.data.repository.SyncRepository
import ec.edu.espoch.omniflow.update.AppUpdater
import kotlinx.coroutines.delay
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.OmniTopBar
import ec.edu.espoch.omniflow.ui.screens.alerts.AlertsScreen
import ec.edu.espoch.omniflow.ui.screens.attendance.AttendanceClassesScreen
import ec.edu.espoch.omniflow.ui.screens.attendance.AttendanceDetailScreen
import ec.edu.espoch.omniflow.ui.screens.attendance.ChangePasswordScreen
import ec.edu.espoch.omniflow.ui.screens.attendance.FaceEnrollScreen
import ec.edu.espoch.omniflow.ui.screens.attendance.MyAttendanceScreen
import android.net.Uri
import ec.edu.espoch.omniflow.ui.screens.dashboard.DashboardScreen
import ec.edu.espoch.omniflow.ui.screens.labs.LabDetailScreen
import ec.edu.espoch.omniflow.ui.screens.labs.LabsScreen
import ec.edu.espoch.omniflow.ui.screens.login.LoginScreen
import ec.edu.espoch.omniflow.ui.screens.monitor.MonitorScreen
import ec.edu.espoch.omniflow.ui.screens.profile.ProfileScreen
import ec.edu.espoch.omniflow.ui.screens.reports.ReportsScreen
import ec.edu.espoch.omniflow.ui.screens.settings.SettingsScreen
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.RedSoft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

object Routes {
    const val DASHBOARD = "dashboard"
    const val LABS = "labs"
    const val MONITOR = "monitor"
    const val REPORTS = "reports"
    const val PROFILE = "profile"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val LAB_DETAIL = "lab/{labId}"
    const val ATTENDANCE = "attendance"
    const val ATTENDANCE_DETAIL = "attendance/{slotId}/{date}"
    const val MY_ATTENDANCE = "my-attendance"
    const val FACE_ENROLL = "face-enroll?cedula={cedula}&name={name}"
    fun attendanceDetail(slotId: Long, date: String) = "attendance/$slotId/$date"
    fun faceEnroll(cedula: String = "", name: String = "") = "face-enroll?cedula=${Uri.encode(cedula)}&name=${Uri.encode(name)}"
    fun labDetail(id: Long) = "lab/$id"
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val user: User) : SessionState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    auth: AuthRepository,
    alerts: AlertRepository,
    seeder: DemoDataSeeder,
    sync: SyncRepository,
    updater: AppUpdater,
) : ViewModel() {
    private val seeded = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            seeder.seedIfNeeded()
            seeded.value = true
            // Horario actualizado (docentes e inscritos de OASIS) si hay backend configurado.
            sync.pullCatalog()
            while (true) {
                updater.check(manual = false)
                delay(AppUpdater.CHECK_INTERVAL_MS)
            }
        }
    }

    val session: StateFlow<SessionState> = combine(auth.currentUser, seeded) { user, ready ->
        when {
            !ready -> SessionState.Loading
            user == null -> SessionState.LoggedOut
            else -> SessionState.LoggedIn(user)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    val unread: StateFlow<Int> = alerts.unreadCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}

@Composable
fun OmniFlowRoot(openAlerts: Boolean, onAlertsOpened: () -> Unit, vm: SessionViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    when (val s = session) {
        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        SessionState.LoggedOut -> LoginScreen()
        is SessionState.LoggedIn ->
            if (s.user.mustChangePassword) ChangePasswordScreen() else MainScaffold(s.user, vm, openAlerts, onAlertsOpened)
    }
}

@Composable
private fun MainScaffold(user: User, vm: SessionViewModel, openAlerts: Boolean, onAlertsOpened: () -> Unit) {
    val nav = rememberNavController()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val items = buildList {
        add(BottomItem(Routes.DASHBOARD, "Inicio", Icons.Default.Home))
        add(BottomItem(Routes.LABS, "Laboratorios", Icons.Default.Science))
        if (user.canMonitor) add(BottomItem(Routes.ATTENDANCE, "Asistencia", Icons.Default.FactCheck))
        if (user.isStudent) add(BottomItem(Routes.MY_ATTENDANCE, "Asistencia", Icons.Default.FactCheck))
        if (user.canMonitor) add(BottomItem(Routes.MONITOR, "Cámara", Icons.Default.Videocam))
        add(BottomItem(Routes.PROFILE, "Perfil", Icons.Default.Person))
    }
    val isTopLevel = items.any { it.route == route }

    LaunchedEffect(openAlerts) {
        if (openAlerts) {
            nav.navigate(Routes.ALERTS) { launchSingleTop = true }
            onAlertsOpened()
        }
    }

    Scaffold(
        topBar = {
            OmniTopBar(
                title = when (route) {
                    Routes.ALERTS -> "Alertas"
                    Routes.SETTINGS -> "Ajustes"
                    Routes.LAB_DETAIL -> "Detalle de laboratorio"
                    Routes.ATTENDANCE_DETAIL -> "Lista de asistencia"
                    Routes.FACE_ENROLL -> "Registro facial"
                    Routes.REPORTS -> "Reportes"
                    else -> "OmniFlow App"
                },
                unread = unread,
                onBack = if (isTopLevel) null else ({ nav.popBackStack() }),
                onBell = if (route == Routes.ALERTS) null else ({ nav.navigate(Routes.ALERTS) { launchSingleTop = true } }),
            )
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(containerColor = Color.White) {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = EspochRed,
                                selectedTextColor = EspochRed,
                                indicatorColor = RedSoft,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    padding,
                    onOpenLab = { nav.navigate(Routes.labDetail(it)) },
                    onNavigate = { r -> if (items.any { it.route == r }) nav.navigateTopLevel(r) else nav.navigate(r) },
                )
            }
            composable(Routes.LABS) { LabsScreen(padding, onOpenLab = { nav.navigate(Routes.labDetail(it)) }) }
            composable(Routes.MONITOR) { MonitorScreen(padding, onOpenSettings = { nav.navigate(Routes.SETTINGS) }) }
            composable(Routes.REPORTS) { ReportsScreen(padding) }
            composable(Routes.ATTENDANCE) {
                AttendanceClassesScreen(padding, onOpen = { id, date -> nav.navigate(Routes.attendanceDetail(id, date)) })
            }
            composable(
                Routes.ATTENDANCE_DETAIL,
                arguments = listOf(navArgument("slotId") { type = NavType.LongType }, navArgument("date") { type = NavType.StringType }),
            ) {
                AttendanceDetailScreen(padding, onEnrollFace = { ced, name -> nav.navigate(Routes.faceEnroll(ced, name)) })
            }
            composable(Routes.MY_ATTENDANCE) { MyAttendanceScreen(padding, onEnroll = { nav.navigate(Routes.faceEnroll()) }) }
            composable(
                Routes.FACE_ENROLL,
                arguments = listOf(
                    navArgument("cedula") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { FaceEnrollScreen(padding, onDone = { nav.popBackStack() }) }
            composable(Routes.PROFILE) { ProfileScreen(padding, onOpenSettings = { nav.navigate(Routes.SETTINGS) }) }
            composable(Routes.ALERTS) { AlertsScreen(padding) }
            composable(Routes.SETTINGS) { SettingsScreen(padding) }
            composable(Routes.LAB_DETAIL, arguments = listOf(navArgument("labId") { type = NavType.LongType })) {
                LabDetailScreen(padding, onMonitor = { nav.navigateTopLevel(Routes.MONITOR) })
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
