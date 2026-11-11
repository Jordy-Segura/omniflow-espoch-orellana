package ec.edu.espoch.omniflow.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ec.edu.espoch.omniflow.BuildConfig
import ec.edu.espoch.omniflow.data.local.AlertDao
import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import ec.edu.espoch.omniflow.data.local.OmniFlowDatabase
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OmniFlowDatabase =
        Room.databaseBuilder(context, OmniFlowDatabase::class.java, "omniflow.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun labDao(db: OmniFlowDatabase): LabDao = db.labDao()
    @Provides fun scheduleDao(db: OmniFlowDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun occupancyDao(db: OmniFlowDatabase): OccupancyDao = db.occupancyDao()
    @Provides fun classSessionDao(db: OmniFlowDatabase): ClassSessionDao = db.classSessionDao()
    @Provides fun alertDao(db: OmniFlowDatabase): AlertDao = db.alertDao()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                // HEADERS y no BODY: el cuerpo SOAP de login lleva contraseñas.
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
            }
        }
        .build()
}
