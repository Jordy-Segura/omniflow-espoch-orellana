package ec.edu.espoch.omniflow.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.edu.espoch.omniflow.data.local.AlertDao
import ec.edu.espoch.omniflow.data.local.AlertEntity
import ec.edu.espoch.omniflow.domain.model.Alert
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.utils.NotificationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AlertDao,
    private val settings: SettingsRepository,
) {
    val alerts: Flow<List<Alert>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    val unreadCount: Flow<Int> = dao.observeUnread()

    fun countSince(since: Long): Flow<Int> = dao.observeCountSince(since)

    /**
     * Registra una alerta respetando un periodo de enfriamiento por laboratorio y tipo,
     * para no saturar al usuario con la misma alerta cada pocos segundos.
     * @return true si se creo una alerta nueva.
     */
    suspend fun raise(
        lab: Lab,
        type: AlertType,
        message: String,
        now: Long = System.currentTimeMillis(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        notify: Boolean = true,
    ): Boolean {
        val last = dao.lastOf(lab.id, type.name)
        if (last != null && now - last < cooldownMs) return false
        val id = dao.insert(
            AlertEntity(labId = lab.id, labName = lab.name, type = type.name, message = message, timestamp = now),
        )
        if (notify) NotificationHelper.show(context, id.toInt(), "${type.title} · ${lab.name}", message, settings.alertSound())
        return true
    }

    suspend fun markRead(id: Long) = dao.markRead(id)
    suspend fun markAllRead() = dao.markAllRead()

    companion object {
        const val DEFAULT_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
