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
import io.github.sshtunnelvpn.data.Ipv6Mode
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
    /** 目前 VPN 介面是否帶 IPv6 位址(IPv6 經通道);false 表示 IPv6 被封鎖。 */
    private var interfaceV6 = false

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
        // 缺帳密(例如匯入不含帳密的備份)時直接說明,不必等伺服器回認證失敗
        if (p.missingCredentials.isNotEmpty()) {
            fail(getString(R.string.error_missing_credentials, p.displayName))
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

        // IPv6:見 Ipv6Mode 說明。AUTO 依上次對這台伺服器的實測結果,未知時先封鎖(安全側)
        val v6 = when (settings.ipv6Mode) {
            Ipv6Mode.TUNNEL -> true
            Ipv6Mode.BLOCK -> false
            Ipv6Mode.AUTO -> container.ipInfo.ipv6Capable(p.id) == true
        }
        interfaceV6 = v6
        controller.update { it.copy(ipv6Routed = v6) }
        val pfd = try {
            buildInterface(p, settings, v6).establish()
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

    private fun buildInterface(p: Profile, s: AppSettings, v6Address: Boolean): Builder {
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
        // IPv6 路由一律接管,避免 IPv6 從實體網路繞過 VPN 洩漏。
        // 只有要讓 IPv6 經通道時才給介面 IPv6 位址;沒有位址時,App 的 IPv6 連線會立即失敗並改用 IPv4,
        // 系統 DNS 也不會再查 AAAA(伺服器沒有 IPv6 時,這比「連上再失敗」快且穩定)。
        addRoutes(b, Routes.ALL_V6, exclude6)
        if (v6Address) b.addAddress(TunAddress.V6, TunAddress.V6_PREFIX)

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
                    udpDropped = st.udpDropped, dialFailures = st.dialFailures,
                    serverIpv4 = st.getIPv4().toInt(), serverIpv6 = st.getIPv6().toInt(),
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
            // 分別查 IPv4(ipinfo.io)與 IPv6(v6.ipinfo.io)出口;查不到的協定通常代表伺服器不支援
            for ((url, v6) in listOf(IpInfoRepository.EXIT_URL to false, IpInfoRepository.EXIT_URL6 to true)) {
                runCatching { Sshvpn.fetchViaTunnel(url, 8000) }
                    .mapCatching { IpInfoRepository.parse(it) ?: error("unexpected response") }
                    .onSuccess {
                        if (v6) ipInfo.recordExit6(profileId, it) else ipInfo.recordExit(profileId, it)
                        logs.add(LogBuffer.INFO, "Exit ${if (v6) "IPv6" else "IPv4"} ${it.ip} (${it.country ?: "?"})")
                    }
                    .onFailure { logs.add(LogBuffer.INFO, "Exit ${if (v6) "IPv6" else "IPv4"} not available: ${it.message}") }
            }
        }
    }

    /**
     * 引擎回報伺服器的 IPv6 能力後:記住結果(按伺服器),AUTO 模式下若與目前介面設定不符,
     * 重建一次 VPN 介面(新介面建立後系統會無縫接手,SSH 會重新連線一次)。之後同一台伺服器直接用記住的結果。
     */
    private fun onServerIp(ipv4: Boolean?, ipv6: Boolean?) {
        val p = profile ?: return
        container.appScope.launch {
            container.ipInfo.recordFamilies(p.id, ipv4, ipv6)
            val available = ipv6 ?: return@launch
            val mode = container.settings.current().ipv6Mode
            if (mode == Ipv6Mode.AUTO && available != interfaceV6) {
                logs.add(
                    LogBuffer.INFO,
                    if (available) "IPv6 auto: enabling IPv6 through tunnel, re-establishing VPN"
                    else "IPv6 auto: blocking IPv6 (apps use IPv4), re-establishing VPN",
                )
                scope.launch { if (running && profile?.id == p.id) startTunnel(p.id) }
            }
        }
    }

    private inner class ServicePlatform : BasePlatform(
        container.knownHosts,
        persistHostKeys = true,
        onMismatch = { controller.reportHostKeyMismatch(it) },
        logs = container.logs,
    ) {
        override fun protect(fd: Long): Boolean = this@TunnelVpnService.protect(fd.toInt())

        override fun onServerIP(ipv4: Long, ipv6: Long) = onServerIp(familyOf(ipv4), familyOf(ipv6))

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

/** Go 端能力值:1 有 / 2 沒有 / 0 無法判定(null)。 */
fun familyOf(v: Long): Boolean? = when (v) {
    1L -> true
    2L -> false
    else -> null
}
