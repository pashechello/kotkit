package com.kotkit.basic.proxy

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.kotkit.basic.App
import com.kotkit.basic.R
import timber.log.Timber

/**
 * Android VpnService that creates a TUN interface and drives tun2socks.
 *
 * Lifecycle (managed by [ProxyManager]):
 *   KotKitVpnService.start(ctx, config)  → sends ACTION_START intent
 *   KotKitVpnService.stop(ctx)           → sends ACTION_STOP intent
 *   KotKitVpnService.isTunnelUp()        → polled by ProxyManager to detect readiness
 *
 * Critical: "com.kotkit.basic" is excluded from the VPN so that the app's own
 * API calls (heartbeat, logs, task reporting) bypass the proxy and reach the backend
 * directly. Only TikTok traffic goes through the tunnel.
 */
class KotKitVpnService : VpnService() {

    companion object {
        private const val TAG = "KotKitVpnService"
        private const val NOTIFICATION_ID = 10099

        const val ACTION_START = "com.kotkit.basic.proxy.VPN_START"
        const val ACTION_STOP  = "com.kotkit.basic.proxy.VPN_STOP"

        private const val EXTRA_HOST     = "proxy_host"
        private const val EXTRA_PORT     = "proxy_port"
        private const val EXTRA_USER     = "proxy_user"
        private const val EXTRA_PASS     = "proxy_pass"
        private const val EXTRA_PROTOCOL = "proxy_protocol"
        private const val EXTRA_SESSION  = "proxy_session_id"

        // Shared state — polled by ProxyManager
        @Volatile var tunnelUp: Boolean = false
            private set

        fun start(context: Context, config: ProxyConfig) {
            val intent = Intent(context, KotKitVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST,     config.host)
                putExtra(EXTRA_PORT,     config.port)
                putExtra(EXTRA_USER,     config.username ?: "")
                putExtra(EXTRA_PASS,     config.password ?: "")
                putExtra(EXTRA_PROTOCOL, config.protocol)
                putExtra(EXTRA_SESSION,  config.sessionId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KotKitVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isTunnelUp(): Boolean = tunnelUp
    }

    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                val host     = intent.getStringExtra(EXTRA_HOST)     ?: return START_NOT_STICKY
                val port     = intent.getIntExtra(EXTRA_PORT, 0)
                val user     = intent.getStringExtra(EXTRA_USER)
                val pass     = intent.getStringExtra(EXTRA_PASS)
                val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "socks5"
                val session  = intent.getStringExtra(EXTRA_SESSION)  ?: ""
                startTunnel(host, port, user, pass, protocol, session)
                START_STICKY
            }
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startTunnel(
        host: String,
        port: Int,
        user: String?,
        pass: String?,
        protocol: String,
        sessionId: String
    ) {
        // Must call startForeground() quickly (within 5s of startForegroundService)
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("KotKit-Proxy-$sessionId")
                .addAddress("10.233.233.1", 30)     // TUN subnet
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addRoute("0.0.0.0", 0)             // All IPv4 through TUN
                .setMtu(1500)
                .setBlocking(false)

            // Exclude our own app — heartbeat/logs must reach kotkit backend directly,
            // not through the proxy. TikTok (com.zhiliaoapp.musically) is NOT excluded.
            try {
                builder.addDisallowedApplication("com.kotkit.basic")
            } catch (e: Exception) {
                Timber.tag(TAG).w("addDisallowedApplication failed: ${e.message}")
            }

            val tun = builder.establish()
            if (tun == null) {
                // Happens if VPN permission was revoked between consent and tunnel setup
                Timber.tag(TAG).e("establish() returned null — VPN consent lost?")
                tunnelUp = false
                return
            }

            tunInterface = tun
            val proxyUrl = buildProxyUrl(protocol, host, port, user, pass)
            Tun2SocksEngine.start(tun.fd, proxyUrl)
            tunnelUp = true
            Timber.tag(TAG).i("VPN tunnel up: fd=${tun.fd} proxyUrl masked")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start VPN tunnel")
            tunnelUp = false
            teardownTun()
        }
    }

    private fun stopTunnel() {
        Timber.tag(TAG).i("Stopping VPN tunnel")
        tunnelUp = false
        try {
            Tun2SocksEngine.stop()
        } catch (e: Exception) {
            Timber.tag(TAG).w("tun2socks stop error: ${e.message}")
        }
        teardownTun()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun teardownTun() {
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w("TUN close error: ${e.message}")
        } finally {
            tunInterface = null
        }
    }

    override fun onRevoke() {
        // System revoked VPN (user enabled another VPN app, or system policy)
        Timber.tag(TAG).w("VPN permission revoked by system")
        tunnelUp = false
        try { Tun2SocksEngine.stop() } catch (_: Exception) {}
        teardownTun()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun buildProxyUrl(
        protocol: String,
        host: String,
        port: Int,
        user: String?,
        pass: String?
    ): String {
        val auth = if (!user.isNullOrBlank() && !pass.isNullOrBlank()) "$user:$pass@" else ""
        return "$protocol://${auth}${host}:$port"
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_POSTING)
            .setContentTitle("VPN прокси активен")
            .setSmallIcon(R.drawable.ic_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }
}
