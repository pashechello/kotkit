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

        // Config is held in-memory and consumed once — never put credentials in Intent extras
        // (extras are visible via `adb shell dumpsys activity services`).
        @Volatile private var pendingConfig: ProxyConfig? = null

        // Shared state — polled by ProxyManager
        @Volatile var tunnelUp: Boolean = false
            private set

        fun start(context: Context, config: ProxyConfig) {
            pendingConfig = config  // pass in-memory, not via Intent extras
            val intent = Intent(context, KotKitVpnService::class.java).apply {
                action = ACTION_START
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
                val config = pendingConfig
                pendingConfig = null  // consume immediately to prevent reuse
                if (config == null) {
                    // Stale restart (e.g. system killed & re-delivered intent with no config).
                    // Do not start tunnel with unknown credentials.
                    Timber.tag(TAG).e("ACTION_START received but no pendingConfig — aborting")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTunnel(config)
                START_NOT_STICKY  // don't auto-restart with stale/null intent
            }
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startTunnel(config: ProxyConfig) {
        // Must call startForeground() quickly (within 5s of startForegroundService)
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("KotKit-Proxy-${config.sessionId}")
                .addAddress("10.233.233.1", 30)     // TUN subnet
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addRoute("0.0.0.0", 0)             // All IPv4 through TUN
                .setMtu(1500)
                .setBlocking(false)

            // Exclude our own app — heartbeat/logs must reach kotkit backend directly,
            // not through the proxy. TikTok (com.zhiliaoapp.musically) is NOT excluded.
            // CRITICAL: if exclusion fails, abort — we must never route our own API
            // traffic through the proxy and risk credential leakage.
            try {
                builder.addDisallowedApplication("com.kotkit.basic")
            } catch (e: Exception) {
                Timber.tag(TAG).e(
                    "addDisallowedApplication failed — aborting tunnel to prevent " +
                    "kotkit API traffic leaking through proxy: ${e.message}"
                )
                tunnelUp = false
                return
            }

            val tun = builder.establish()
            if (tun == null) {
                // Happens if VPN permission was revoked between consent and tunnel setup
                Timber.tag(TAG).e("establish() returned null — VPN consent lost?")
                tunnelUp = false
                return
            }

            tunInterface = tun
            Tun2SocksEngine.start(tun.fd, config.toProxyUrl())
            tunnelUp = true
            Timber.tag(TAG).i("VPN tunnel up: fd=${tun.fd} host=${config.host}:${config.port}")

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

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_VPN)
            .setContentTitle("VPN прокси активен")
            .setSmallIcon(R.drawable.ic_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }
}
