package io.github.sshtunnelvpn.tunnel

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.sshtunnelvpn.App
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AppMode
import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.IpInfoRepository
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.sshvpn.Sshvpn
import io.github.sshtunnelvpn.ui.MainActivity
import io.github.sshtunnelvpn.ui.formatRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelVpnService : VpnService() {
    // 所有啟停操作都在這條單執行緒 dispatcher 上序列化執行,不需要鎖
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var statsJob: Job? = null
    private var running = false
    private var profile: Profile? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotificationText: String? = null

    private val controller get() = container.tunnel
    private val logs get() = container.logs

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopTunnel(null) }
            // ACTION_START,或 always-on VPN / 系統重啟服務時 action 為 SERVICE_INTERFACE / null
            else -> {
                startForegroundCompat(buildNotification(getString(R.string.state_connecting)))
                val id = intent?.getStringExtra(EXTRA_PROFILE_ID)
                scope.launch { startTunnel(id) }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private suspend fun startTunnel(requestedId: String?) {
        val settingsRepo = container.settings
        var settings = settingsRepo.current()
        val id = requestedId ?: settings.selectedProfileId
        val p = id?.let { container.profiles.get(it) }
        if (p == null) {
            fail(getString(R.string.error_no_profile))
            return
        }
        if (id != settings.selectedProfileId) {
            settingsRepo.update { it.copy(selectedProfileId = id) }
            settings = settingsRepo.current()
        }
        // 切換伺服器時不先停舊引擎:先 establish 新介面(系統會無縫接手),
        // Sshvpn.start 內部再替換掉舊引擎,避免中間出現流量繞過 VPN 的空窗
        profile = p
        controller.resetHistory()
        controller.dismissHostKeyMismatch()
        controller.update { TunnelStatus(TunnelState.CONNECTING, p.id, p.displayName) }
        logs.add(LogBuffer.INFO, "Connecting to ${p.endpoint}")

        val pfd = try {
            buildInterface(p, settings).establish()
        } catch (e: Exception) {
            fail(getString(R.string.error_vpn_interface, e.message ?: e.toString()))
            return
        }
        if (pfd == null) {
            // establish() 回傳 null = VPN 授權被撤銷
            fail(getString(R.string.error_vpn_permission))
            return
        }
        val platform = ServicePlatform()
        try {
            Sshvpn.start(pfd.detachFd().toLong(), EngineConfig.json(p, settings, TunAddress.DNS_V4), platform)
        } catch (e: Exception) {
            fail(getString(R.string.error_engine_start, e.message ?: e.toString()))
            return
        }
        running = true
        registerNetworkCallback()
        startStatsLoop()
    }

    private fun buildInterface(p: Profile, s: AppSettings): Builder {
        val b = Builder()
            .setSession(p.displayName)
            .setMtu(s.mtu)
            .addAddress(TunAddress.V4, TunAddress.V4_PREFIX)
            .addDnsServer(TunAddress.DNS_V4)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val custom = Cidr.parseList(s.excludedRoutes)
        val exclude4 = (if (s.bypassLan) Routes.LAN_V4 else emptyList()) + custom.filter { it.bits == 32 }
        val exclude6 = (if (s.bypassLan) Routes.LAN_V6 else emptyList()) + custom.filter { it.bits == 128 }
        addRoutes(b, Routes.ALL_V4, exclude4)
        if (s.ipv6) {
            b.addAddress(TunAddress.V6, TunAddress.V6_PREFIX)
            addRoutes(b, Routes.ALL_V6, exclude6)
        }

        val self = packageName
        when (s.appMode) {
            AppMode.ALLOW -> {
                val pkgs = s.appPackages.filter { it != self && isInstalled(it) }
                if (pkgs.isEmpty()) b.addDisallowedApplication(self) else pkgs.forEach { b.addAllowedApplication(it) }
            }
            AppMode.DISALLOW -> {
                b.addDisallowedApplication(self)
                s.appPackages.filter { it != self && isInstalled(it) }.forEach { b.addDisallowedApplication(it) }
            }
            AppMode.ALL -> b.addDisallowedApplication(self)
        }
        return b
    }

    private fun addRoutes(b: Builder, all: Cidr, exclude: List<Cidr>) {
        val plan = Routes.plan(Build.VERSION.SDK_INT, all, exclude)
        plan.include.forEach { b.addRoute(it.address, it.prefix) }
        if (Build.VERSION.SDK_INT >= 33) plan.exclude.forEach { b.excludeRoute(IpPrefix(it.address, it.prefix)) }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                if (current != null && current != network) {
                    logs.add(LogBuffer.INFO, "Default network changed")
                    Sshvpn.networkChanged()
                }
                current = network
            }

            override fun onLost(network: Network) {
                if (current == network) setUnderlyingNetworks(null)
            }
        }
        // 本 app 已排除於 VPN 之外,所以這裡拿到的是實體網路
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var prev: Pair<Long, Long>? = null
            var prevTime = System.nanoTime()
            while (isActive) {
                delay(1000)
                val st = Sshvpn.getStats() ?: continue
                val now = System.nanoTime()
                val dt = (now - prevTime).coerceAtLeast(1) / 1e9
                val rxRate = prev?.let { ((st.rxBytes - it.first) / dt).toLong() } ?: 0
                val txRate = prev?.let { ((st.txBytes - it.second) / dt).toLong() } ?: 0
                prev = st.rxBytes to st.txBytes
                prevTime = now
                val stats = TrafficStats(
                    rxBytes = st.rxBytes, txBytes = st.txBytes, rxRate = rxRate, txRate = txRate,
                    tcpActive = st.tcpActive, tcpTotal = st.tcpTotal, udpActive = st.udpActive,
                    dnsQueries = st.dnsQueries, dnsCacheHits = st.dnsCacheHits, rttMillis = st.rttMillis,
                    sshLive = st.sshLive, sshTotal = st.sshTotal, uptimeMillis = st.uptimeMillis,
                    serverVersion = st.serverVersion,
                )
                controller.update { it.copy(stats = stats) }
                controller.pushSample(TrafficSample(System.currentTimeMillis(), rxRate, txRate))
                if (controller.status.value.state == TunnelState.CONNECTED) {
                    notifyText("↓ ${formatRate(rxRate)}   ↑ ${formatRate(txRate)}")
                }
            }
        }
    }

    private fun notifyText(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_TUNNEL)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle(profile?.displayName ?: getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun fail(message: String) {
        logs.add(LogBuffer.ERROR, message)
        stopTunnel(message)
    }

    private fun stopTunnel(error: String?) {
        statsJob?.cancel()
        statsJob = null
        unregisterNetworkCallback()
        if (running) {
            controller.update { it.copy(state = TunnelState.STOPPING) }
            Sshvpn.stop()
            running = false
        }
        controller.update {
            TunnelStatus(
                state = if (error != null) TunnelState.ERROR else TunnelState.IDLE,
                profileId = it.profileId,
                profileName = it.profileName,
                error = error,
                stats = it.stats,
            )
        }
        lastNotificationText = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        logs.add(LogBuffer.WARN, "VPN permission revoked by system")
        scope.launch { stopTunnel(null) }
    }

    override fun onDestroy() {
        if (running) {
            Sshvpn.stop()
            running = false
            controller.update { it.copy(state = TunnelState.IDLE) }
        }
        unregisterNetworkCallback()
        scope.cancel()
        super.onDestroy()
    }

    /** 經通道查出口 IP/國家;同一伺服器 10 分鐘內不重查。不放在 serial scope,以免慢查詢卡住啟停。 */
    private fun checkExit(profileId: String) {
        val ipInfo = container.ipInfo
        container.appScope.launch(Dispatchers.IO) {
            if (ipInfo.exitCheckedRecently(profileId)) return@launch
            runCatching { Sshvpn.fetchViaTunnel(IpInfoRepository.EXIT_URL, 8000) }
                .mapCatching { IpInfoRepository.parse(it) ?: error("unexpected response") }
                .onSuccess {
                    ipInfo.recordExit(profileId, it)
                    logs.add(LogBuffer.INFO, "Exit IP ${it.ip} (${it.country ?: "?"})")
                }
                .onFailure { logs.add(LogBuffer.WARN, "Exit IP check failed: ${it.message}") }
        }
    }

    private inner class ServicePlatform : BasePlatform(
        container.knownHosts,
        persistHostKeys = true,
        onMismatch = { controller.reportHostKeyMismatch(it) },
        logs = container.logs,
    ) {
        override fun protect(fd: Long): Boolean = this@TunnelVpnService.protect(fd.toInt())

        override fun onState(state: Long, message: String) {
            when (state.toInt()) {
                Sshvpn.StateConnecting.toInt() -> controller.update { it.copy(state = TunnelState.CONNECTING) }
                Sshvpn.StateConnected.toInt() -> {
                    controller.update { it.copy(state = TunnelState.CONNECTED, error = null) }
                    notifyText(getString(R.string.state_connected))
                    profile?.let { checkExit(it.id) }
                }
                Sshvpn.StateReconnecting.toInt() -> {
                    controller.update { it.copy(state = TunnelState.RECONNECTING) }
                    notifyText(getString(R.string.state_reconnecting))
                }
                Sshvpn.StateError.toInt() -> scope.launch { stopTunnel(message) }
            }
        }
    }

    companion object {
        const val ACTION_START = "io.github.sshtunnelvpn.START"
        const val ACTION_STOP = "io.github.sshtunnelvpn.STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 1
    }
}
