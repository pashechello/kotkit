package com.kotkit.basic.proxy

import timber.log.Timber

/**
 * JNI/AAR bridge to the tun2socks engine.
 *
 * The underlying library (xjasonlyu/tun2socks, Go, MIT) forwards all traffic from
 * a TUN file descriptor through a SOCKS5 (or HTTP) proxy URL.
 *
 * ─── Build instructions (one-time) ────────────────────────────────────────────
 *   go install golang.org/x/mobile/cmd/gomobile@latest
 *   go install golang.org/x/mobile/cmd/gobind@latest
 *   gomobile init
 *
 *   git clone https://github.com/xjasonlyu/tun2socks.git
 *   cd tun2socks
 *   gomobile bind -target=android/arm64,android/amd64 \
 *       -o tun2socks.aar -javapkg tun2socks ./engine/...
 *
 *   # Copy to kotkit-basic/app/libs/tun2socks.aar
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * The gomobile-generated Java class name depends on the Go package name.
 * For `package engine` the class is `engine.Engine` (or similar).
 * Verify with: `jar tf tun2socks.aar` before finalising the calls below.
 *
 * Current assumption: Go package = "tun2socks", exported type = "Tun2socks"
 * → generated class: `tun2socks.Tun2socks`
 */
object Tun2SocksEngine {

    private const val TAG = "Tun2SocksEngine"

    @Volatile private var running = false

    /**
     * Start the tun2socks engine.
     *
     * @param tunFd   File descriptor of the TUN interface (from VpnService.Builder.establish())
     * @param proxyUrl SOCKS5/HTTP proxy URL, e.g. "socks5://user:pass@1.2.3.4:1080"
     */
    fun start(tunFd: Int, proxyUrl: String) {
        if (running) {
            Timber.tag(TAG).w("Engine already running — stopping first")
            stop()
        }
        Timber.tag(TAG).i("Starting tun2socks: fd=$tunFd proxy=${proxyUrl.substringBefore("@").substringBefore("//").plus("//***@").plus(proxyUrl.substringAfter("@"))}")
        // TODO: replace with real gomobile call once tun2socks.aar is in app/libs/
        // tun2socks.Tun2socks.start(tunFd, proxyUrl, "warn")
        running = true
    }

    /**
     * Stop the tun2socks engine. Safe to call when not running.
     */
    fun stop() {
        if (!running) return
        Timber.tag(TAG).i("Stopping tun2socks")
        try {
            // TODO: replace with real gomobile call
            // tun2socks.Tun2socks.stop()
        } catch (e: Exception) {
            Timber.tag(TAG).w("tun2socks stop error: ${e.message}")
        } finally {
            running = false
        }
    }

    fun isRunning(): Boolean = running
}
