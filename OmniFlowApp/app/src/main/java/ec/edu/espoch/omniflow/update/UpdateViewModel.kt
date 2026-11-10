package ec.edu.espoch.omniflow.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state
    val autoUpdate: StateFlow<Boolean> = settings.autoUpdate.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val versionName = updater.currentVersionName
    val versionCode = updater.currentVersionCode

    fun check() = viewModelScope.launch { updater.check(manual = true) }

    fun install() = viewModelScope.launch {
        when (val s = updater.state.value) {
            is UpdateState.Available -> updater.downloadAndInstall(s.release)
            else -> updater.check(manual = true)
        }
    }

    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { settings.setAutoUpdate(v) }

    fun openPermission(context: Context) = context.startActivity(updater.unknownSourcesSettingsIntent())

    fun confirm(context: Context) {
        val s = updater.state.value
        if (s is UpdateState.NeedsConfirmation) {
            context.startActivity(s.intent)
            updater.confirmationShown()
        }
    }
}
