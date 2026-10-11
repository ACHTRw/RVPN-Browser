// Command rvpn-core is the RVPN Browser engine.
//
// It is normally launched by the browser as a Chrome native messaging host and
// speaks the JSON protocol documented in docs/PROTOCOL.md over stdin/stdout.
// The same binary is also usable from a terminal for diagnostics.
//
// Usage:
//
//	rvpn-core                       # native messaging host (default)
//	rvpn-core --version
//	rvpn-core --once '{"cmd":"hello"}'
//	rvpn-core --selftest vpn.zju.edu.cn:443
//	rvpn-core --tcp 127.0.0.1:0 --token-file /tmp/rvpn.token
//	rvpn-core --log-file /tmp/rvpn-core.log
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/ACHTRw/RVPN-Browser/core/rvpn"
)

func main() {
	os.Exit(run())
}

func run() int {
	var (
		showVersion = flag.Bool("version", false, "print version and exit")
		onceCmd     = flag.String("once", "", "handle a single JSON request and print the JSON reply")
		selfTestArg = flag.String("selftest", "", "test TCP reachability of <host:port> and exit")
		tcpAddr     = flag.String("tcp", "", "serve the JSON protocol on a loopback TCP address (e.g. 127.0.0.1:0)")
		tokenFile   = flag.String("token-file", "", "write the TCP mode authentication token to this file")
		logFile     = flag.String("log-file", "", "append diagnostics to this file")
		debugFlag   = flag.Bool("debug", false, "enable debug logging")
		printEnums  = flag.Bool("enums", false, "print protocol constants and exit")
	)
	flag.Parse()

	if *printEnums {
		fmt.Printf("phases: %s\n", strings.Join([]string{
			rvpn.PhaseIdle, rvpn.PhaseConnecting, rvpn.PhaseAuthenticating,
			rvpn.PhaseConnected, rvpn.PhaseError, rvpn.PhaseStopping,
		}, ", "))
		fmt.Printf("capabilities: %s\n", strings.Join(rvpn.Capabilities(), ", "))
		return 0
	}

	if *showVersion {
		fmt.Printf("rvpn-core %s (zju-connect protocol library)\n", rvpn.Version)
		return 0
	}

	// Diagnostics never go to stdout in native messaging mode.
	var diag io.Writer = os.Stderr
	if *logFile != "" {
		f, err := os.OpenFile(*logFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			fmt.Fprintf(os.Stderr, "cannot open log file: %v\n", err)
			return 1
		}
		defer func() { _ = f.Close() }()
		diag = f
	}
	logf := func(format string, args ...any) {
		fmt.Fprintf(diag, "[%s] %s\n", time.Now().Format(time.RFC3339), fmt.Sprintf(format, args...))
	}

	if *debugFlag {
		rvpn.SetDebug(true)
	}

	handler := rvpn.NewHandler()
	defer handler.Session().Close()

	switch {
	case *selfTestArg != "":
		req, _ := json.Marshal(map[string]any{"cmd": "selftest", "server": hostOf(*selfTestArg), "port": portOf(*selfTestArg)})
		fmt.Println(string(handler.HandleRaw(req)))
		return 0

	case *onceCmd != "":
		fmt.Println(string(handler.HandleRaw([]byte(*onceCmd))))
		return 0

	case *tcpAddr != "":
		// Loopback TCP mode for front-ends without native messaging support.
		server, err := rvpn.ServeTCP(handler, *tcpAddr, "")
		if err != nil {
			logf("cannot serve TCP: %v", err)
			return 1
		}
		if *tokenFile != "" {
			if err := os.WriteFile(*tokenFile, []byte(server.Token()), 0o600); err != nil {
				logf("cannot write token file: %v", err)
				return 1
			}
		}
		fmt.Printf("RVPN_CORE_LISTENING=%s\n", server.Addr())
		if *tokenFile == "" {
			fmt.Printf("RVPN_CORE_TOKEN=%s\n", server.Token())
		}
		_ = os.Stdout.Sync()

		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
		select {
		case <-sigCh:
		case <-handler.ShutdownRequested():
		}
		_ = server.Close()
		return 0

	default:
		// Native messaging mode. Chrome passes the calling origin as argv[1].
		if err := rvpn.ServeStdio(handler, os.Stdin, os.Stdout); err != nil {
			logf("native messaging stopped: %v", err)
			return 1
		}
		return 0
	}
}

func hostOf(addr string) string {
	if i := strings.LastIndex(addr, ":"); i > 0 {
		return addr[:i]
	}
	return addr
}

func portOf(addr string) int {
	i := strings.LastIndex(addr, ":")
	if i < 0 {
		return 0
	}
	var port int
	_, _ = fmt.Sscanf(addr[i+1:], "%d", &port)
	return port
}
