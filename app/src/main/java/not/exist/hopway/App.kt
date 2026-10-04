package not.exist.hopway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import not.exist.hopway.data.AppSettings
import not.exist.hopway.data.IpInfoRepository
import not.exist.hopway.data.IpInfoStore
import not.exist.hopway.data.KnownHostStore
import not.exist.hopway.data.KnownHostsRepository
import not.exist.hopway.data.ProfileRepository
import not.exist.hopway.data.ProfileStore
import not.exist.hopway.data.SettingsRepository
import not.exist.hopway.data.jsonDataStore
import not.exist.hopway.tunnel.LogBuffer
import not.exist.hopway.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_TUNNEL, getString(R.string.channel_tunnel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_TUNNEL = "tunnel"
    }
}

/** 手動 DI:App 規模不大,不值得引入 Hilt。 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profiles = ProfileRepository(
        jsonDataStore(context, "profiles.bin", ProfileStore.serializer(), ProfileStore(), encrypted = true),
    )
    val settings = SettingsRepository(jsonDataStore(context, "settings.json", AppSettings.serializer(), AppSettings()))
    val knownHosts = KnownHostsRepository(
        jsonDataStore(context, "known_hosts.json", KnownHostStore.serializer(), KnownHostStore()),
    )
    val ipInfo = IpInfoRepository(
        jsonDataStore(context, "ipinfo.json", IpInfoStore.serializer(), IpInfoStore()),
    )
    val logs = LogBuffer()
    val tunnel = TunnelController(context.applicationContext)
}

val Context.container: AppContainer get() = (applicationContext as App).container
