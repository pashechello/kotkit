// Package tun2socks provides a gomobile-compatible wrapper around the
// xjasonlyu/tun2socks engine for use in Android VpnService.
package tun2socks

import (
	"fmt"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// Start configures the tun2socks engine and starts routing traffic from
// the given TUN file descriptor through the proxy.
//
// tunFd:    File descriptor of the TUN interface (from VpnService.Builder.establish())
// proxyUrl: SOCKS5/HTTP proxy URL, e.g. "socks5://user:pass@1.2.3.4:1080"
// logLevel: Log verbosity: "debug", "info", "warn", "error", "silent"
func Start(tunFd int, proxyUrl string, logLevel string) {
	engine.Insert(&engine.Key{
		Device:   fmt.Sprintf("fd://%d", tunFd),
		Proxy:    proxyUrl,
		LogLevel: logLevel,
		MTU:      1500,
	})
	engine.Start()
}

// Stop shuts down the tun2socks engine and releases all resources.
func Stop() {
	engine.Stop()
}
