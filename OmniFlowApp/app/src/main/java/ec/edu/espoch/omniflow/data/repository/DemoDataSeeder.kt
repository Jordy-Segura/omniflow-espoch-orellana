package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carga inicial: los laboratorios de computo de la ESPOCH Sede Orellana y su horario oficial del periodo
 * (assets/lab_schedule.json: PDF del horario + docentes e inscritos de OASIS). Si hay backend, luego se
 * actualiza desde el servidor. No se generan datos simulados; se borran los que dejaron versiones anteriores.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val catalog: CatalogRepository,
    private val occupancyDao: OccupancyDao,
    private val sessionDao: ClassSessionDao,
    private val settings: SettingsRepository,
) {
    suspend fun seedIfNeeded() {
        if (settings.seedVersion() < SEED_VERSION) {
            catalog.applyBundled()
            // Ya no se generan datos simulados: los reportes muestran solo lo que registran las camaras.
            // Se borra la historia simulada que hubieran dejado versiones anteriores.
            clearDemoData()
            settings.setSeedVersion(SEED_VERSION)
        }
    }

    suspend fun clearDemoData() {
        sessionDao.deleteDemo()
        occupancyDao.deleteDemo()
    }

    companion object {
        const val DEMO = "DEMO"
        /** Subirlo obliga a recargar el horario incluido en la app (p. ej. al cambiar de periodo). 4: carreras de la Sede Orellana; 5: base local nueva (personas del modo demostración). */
        const val SEED_VERSION = 5
    }
}
