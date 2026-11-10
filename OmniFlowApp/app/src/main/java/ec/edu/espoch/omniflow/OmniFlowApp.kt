package ec.edu.espoch.omniflow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ec.edu.espoch.omniflow.utils.NotificationHelper

@HiltAndroidApp
class OmniFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
