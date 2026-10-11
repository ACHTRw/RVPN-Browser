// Package rvpncore is the gomobile binding of the RVPN Browser core.
//
// Android front-ends use two entry points:
//
//   - Request(json) for direct in-process calls (simple integrations),
//   - StartControlServer() to expose the very same JSON protocol on a loopback
//     TCP port, which lets the Go engine live in its own Android process while
//     the UI/browser talks to it exactly like the desktop extension talks to
//     the native messaging host.
//
// Keeping the surface to string-in/string-out avoids gomobile interface
// limitations and guarantees identical behaviour across platforms.
package rvpncore

import (
	"sync"

	"github.com/ACHTRw/RVPN-Browser/core/rvpn"
)

var (
	mu          sync.Mutex
	handler     *rvpn.Handler
	serverAddr  string
	serverToken string
	serverClose func() error
)

func ensureHandler() *rvpn.Handler {
	if handler == nil {
		handler = rvpn.NewHandler()
	}
	return handler
}

// Request handles one JSON request and returns the JSON reply. It never
// panics: failures are reported inside the JSON reply.
func Request(requestJSON string) string {
	mu.Lock()
	defer mu.Unlock()
	return string(ensureHandler().HandleRaw([]byte(requestJSON)))
}

// StartControlServer starts the loopback control server used by front-ends that
// run the engine in a separate process. It returns the listening address
// ("127.0.0.1:port") or an empty string on failure.
func StartControlServer() string {
	mu.Lock()
	defer mu.Unlock()
	if serverClose != nil {
		return serverAddr
	}
	srv, err := rvpn.ServeTCP(ensureHandler(), "127.0.0.1:0", "")
	if err != nil {
		return ""
	}
	serverAddr = srv.Addr()
	serverToken = srv.Token()
	serverClose = srv.Close
	return serverAddr
}

// ControlServerToken returns the shared secret required by control clients.
func ControlServerToken() string {
	mu.Lock()
	defer mu.Unlock()
	return serverToken
}

// StopControlServer stops the control server; the VPN session keeps running
// until Shutdown is called.
func StopControlServer() {
	mu.Lock()
	closer := serverClose
	serverClose = nil
	serverAddr = ""
	serverToken = ""
	mu.Unlock()
	if closer != nil {
		_ = closer()
	}
}

// Shutdown stops the VPN session, the control server and releases resources.
func Shutdown() {
	StopControlServer()
	mu.Lock()
	defer mu.Unlock()
	if handler != nil {
		handler.Session().Close()
		handler = nil
	}
}

// Version returns the core version string.
func Version() string {
	return rvpn.Version
}
