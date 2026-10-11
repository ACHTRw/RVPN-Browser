package rvpn

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/things-go/go-socks5"
)

// dialFunc is the signature shared by the zju-connect dialer and the SOCKS5
// library.
type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// proxySet owns the local SOCKS5 and HTTP proxies used by the browser.
//
// Both listeners are created explicitly (instead of letting the upstream
// helper functions create them) so that the session can report the real port
// to the front-end before announcing the "connected" state, and so that a
// restart never leaks a listening socket.
type proxySet struct {
	socksLn   net.Listener
	httpLn    net.Listener
	httpSrv   *http.Server
	bus       *eventBus
	logger    *log.Logger
	closing   sync.Once
	wg        sync.WaitGroup
	socksAddr string
	httpAddr  string
}

type socksLogger struct{ bus *eventBus }

func (l socksLogger) Errorf(format string, arg ...interface{}) {
	l.bus.log(LevelWarn, "[SOCKS5] "+format, arg...)
}

// startProxies binds the proxy listeners and starts serving. It returns as
// soon as both listeners are accepting connections.
func startProxies(cfg *Config, dial dialFunc, resolver socks5.NameResolver, bus *eventBus, sink io.Writer) (*proxySet, error) {
	ps := &proxySet{bus: bus, logger: log.New(sink, "[HTTP] ", log.LstdFlags)}

	socksLn, err := net.Listen("tcp", cfg.SocksBind)
	if err != nil {
		return nil, fmt.Errorf("SOCKS5 监听失败 / cannot listen on %s: %w", cfg.SocksBind, err)
	}
	ps.socksLn = socksLn
	ps.socksAddr = socksLn.Addr().String()

	enableHTTP := cfg.HTTPBind != "" && cfg.HTTPBind != "-"
	if enableHTTP {
		httpLn, err := net.Listen("tcp", cfg.HTTPBind)
		if err != nil {
			_ = socksLn.Close()
			return nil, fmt.Errorf("HTTP 代理监听失败 / cannot listen on %s: %w", cfg.HTTPBind, err)
		}
		ps.httpLn = httpLn
		ps.httpAddr = httpLn.Addr().String()
	}

	socksSrv := socks5.NewServer(
		socks5.WithAuthMethods([]socks5.Authenticator{socks5.NoAuthAuthenticator{}}),
		socks5.WithResolver(resolver),
		socks5.WithDial(dial),
		socks5.WithLogger(socksLogger{bus: bus}),
	)
	ps.wg.Add(1)
	go func() {
		defer ps.wg.Done()
		if err := socksSrv.Serve(socksLn); err != nil && !errors.Is(err, net.ErrClosed) {
			bus.log(LevelError, "SOCKS5 服务退出 / server stopped: %v", err)
		}
	}()

	if enableHTTP {
		handler := &httpProxyHandler{dial: dial, bus: bus}
		ps.httpSrv = &http.Server{
			Handler:           handler,
			ReadHeaderTimeout: 20 * time.Second,
			IdleTimeout:       120 * time.Second,
			ErrorLog:          ps.logger,
		}
		ps.wg.Add(1)
		go func() {
			defer ps.wg.Done()
			if err := ps.httpSrv.Serve(ps.httpLn); err != nil && !errors.Is(err, http.ErrServerClosed) {
				bus.log(LevelError, "HTTP 代理服务退出 / server stopped: %v", err)
			}
		}()
	}

	if ps.httpAddr != "" {
		bus.log(LevelInfo, "本地代理已就绪：SOCKS5 %s，HTTP %s", ps.socksAddr, ps.httpAddr)
	} else {
		bus.log(LevelInfo, "本地代理已就绪：SOCKS5 %s", ps.socksAddr)
	}
	return ps, nil
}

// Close stops both proxies and waits (bounded) for the serving goroutines.
func (ps *proxySet) Close() {
	if ps == nil {
		return
	}
	ps.closing.Do(func() {
		if ps.httpSrv != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			_ = ps.httpSrv.Shutdown(ctx)
			cancel()
		}
		if ps.socksLn != nil {
			_ = ps.socksLn.Close()
		}
		if ps.httpLn != nil {
			_ = ps.httpLn.Close()
		}
		done := make(chan struct{})
		go func() { ps.wg.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	})
}

type httpProxyHandler struct {
	dial dialFunc
	bus  *eventBus
}

func (h *httpProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		h.handleConnect(w, r)
		return
	}
	h.handlePlain(w, r)
}

func (h *httpProxyHandler) handleConnect(w http.ResponseWriter, r *http.Request) {
	target := r.Host
	if target == "" {
		target = r.URL.Host
	}
	if target == "" {
		http.Error(w, "missing target host", http.StatusBadRequest)
		return
	}
	if _, _, err := net.SplitHostPort(target); err != nil {
		target = net.JoinHostPort(target, "443")
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		return
	}
	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	upstream, err := h.dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		_, _ = clientConn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		_ = clientConn.Close()
		h.bus.log(LevelWarn, "HTTP CONNECT %s 失败 / failed: %v", target, err)
		return
	}

	if _, err := clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		_ = clientConn.Close()
		_ = upstream.Close()
		return
	}
	tunnel(clientConn, upstream)
}

func (h *httpProxyHandler) handlePlain(w http.ResponseWriter, r *http.Request) {
	if !r.URL.IsAbs() {
		http.Error(w, "this is a proxy server, requests must use an absolute URL", http.StatusBadRequest)
		return
	}

	outReq := r.Clone(r.Context())
	outReq.RequestURI = ""
	removeHopByHopHeaders(outReq.Header)

	transport := &http.Transport{
		DialContext:           h.dial,
		MaxIdleConns:          64,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   20 * time.Second,
		ExpectContinueTimeout: 2 * time.Second,
		ForceAttemptHTTP2:     false,
	}
	defer transport.CloseIdleConnections()

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer func() { _ = resp.Body.Close() }()

	removeHopByHopHeaders(resp.Header)
	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	if _, err := io.Copy(w, resp.Body); err != nil {
		h.bus.log(LevelDebug, "HTTP 转发中断 / copy failed: %v", err)
	}
}

func removeHopByHopHeaders(h http.Header) {
	for _, f := range h["Connection"] {
		for _, sf := range strings.Split(f, ",") {
			if sf = strings.TrimSpace(sf); sf != "" {
				h.Del(sf)
			}
		}
	}
	for _, k := range []string{
		"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
		"Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade",
	} {
		h.Del(k)
	}
}

type closeWriter interface{ CloseWrite() error }

// tunnel copies bytes in both directions between the browser and the upstream
// connection until either side is done.
func tunnel(clientConn, upstream net.Conn) {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(upstream, clientConn)
		if cw, ok := upstream.(closeWriter); ok {
			_ = cw.CloseWrite()
		} else {
			_ = upstream.Close()
		}
	}()

	_, _ = io.Copy(clientConn, upstream)
	_ = clientConn.Close()

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
	_ = upstream.Close()
}

// normalizeBind turns a user supplied bind string into a listen address,
// defaulting the host to the loopback interface.
func normalizeBind(bind string, defaultPort int) string {
	if bind == "" {
		return net.JoinHostPort("127.0.0.1", strconv.Itoa(defaultPort))
	}
	return bind
}
