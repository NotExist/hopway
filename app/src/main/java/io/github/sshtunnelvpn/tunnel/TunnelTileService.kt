package io.github.sshtunnelvpn.tunnel

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TunnelTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            container.tunnel.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    private fun render(s: TunnelStatus) {
        val tile = qsTile ?: return
        tile.state = when (s.state) {
            TunnelState.CONNECTED, TunnelState.CONNECTING, TunnelState.RECONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name_short)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (s.state) {
                TunnelState.CONNECTED -> s.profileName
                TunnelState.CONNECTING -> getString(R.string.state_connecting)
                TunnelState.RECONNECTING -> getString(R.string.state_reconnecting)
                else -> null
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        val tunnel = container.tunnel
        if (tunnel.isActive) {
            tunnel.disconnect()
            return
        }
        if (tunnel.prepare() != null) {
            // 需要 VPN 授權:交給 Activity 處理
            openApp()
            return
        }
        tunnel.connect()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setAction(MainActivity.ACTION_CONNECT)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
