package rvpn

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/mythologyli/zju-connect/client"
	atrustclient "github.com/mythologyli/zju-connect/client/atrust"
	"github.com/mythologyli/zju-connect/client/atrust/auth"
	easyconnectclient "github.com/mythologyli/zju-connect/client/easyconnect"
	"github.com/mythologyli/zju-connect/dial"
	"github.com/mythologyli/zju-connect/resolve"
	"github.com/mythologyli/zju-connect/service"
	"github.com/mythologyli/zju-connect/stack"
	"github.com/mythologyli/zju-connect/stack/gvisor"
	"github.com/mythologyli/zju-connect/stack/tcptunnel"
	"github.com/mythologyli/zju-connect/underlay"
)

// engine owns every resource of one RVPN session. It is created per start and
// discarded on stop, which keeps repeated start/stop cycles free of leaked
// listeners and goroutines.
type engine struct {
	cfg        *Config
	bus        *eventBus
	challenges *challengeManager
	sink       io.Writer

	underlay  *underlay.Dialer
	tracker   *trackingDialer
	client    client.Client
	stack     stack.Stack
	resolver  *resolve.Resolver
	dialer    *dial.Dialer
	proxies   *proxySet
	keepAlive context.CancelFunc

	closeOnce sync.Once
}

func newEngine(cfg *Config, bus *eventBus, cm *challengeManager, sink io.Writer) *engine {
	return &engine{cfg: cfg, bus: bus, challenges: cm, sink: sink}
}

func (e *engine) log(level, format string, args ...any) {
	e.bus.log(level, format, args...)
}

// connect builds the whole chain: underlay -> protocol client -> L3/TCP stack
// -> resolver -> dialer -> local proxies.
func (e *engine) connect(ctx context.Context) error {
	registerSecrets(e.cfg.Password, e.cfg.TOTPSecret)

	e.log(LevelInfo, "连接参数：%s", e.cfg.Summary())

	underlayDialer, err := underlay.New(underlay.Options{
		AutoDetect:     false,
		LocalDNSServer: "",
	})
	if err != nil {
		return fmt.Errorf("创建底层拨号器失败 / cannot create underlay dialer: %w", err)
	}
	e.underlay = underlayDialer
	e.tracker = newTrackingDialer(underlayDialer)

	if err := e.setupClient(ctx); err != nil {
		return err
	}

	e.log(LevelInfo, "VPN 客户端已建立 / VPN client established")
	e.bus.setPhase(PhaseAuthenticating, "正在获取网络资源 / fetching network resources")

	ipResources, domainResources, dnsResource, err := e.collectResources()
	if err != nil {
		return err
	}

	if clientIP, ipErr := e.client.IP(); ipErr == nil && clientIP != nil {
		e.bus.setClientIP(clientIP.String())
		e.log(LevelInfo, "已获取 VPN 内网地址 / client IP: %s", clientIP.String())
	}

	if err := e.setupStack(ipResources); err != nil {
		return err
	}
	if err := e.setupResolver(ctx, domainResources, dnsResource); err != nil {
		return err
	}

	e.dialer = dial.NewDialer(e.stack, e.resolver, ipResources, e.cfg.ProxyAll, e.cfg.DialDirectProxy)

	proxies, err := startProxies(e.cfg, e.dialer.DialIPPort, e.resolver, e.bus, e.sink)
	if err != nil {
		return err
	}
	e.proxies = proxies

	keepAliveCtx, cancel := context.WithCancel(context.Background())
	e.keepAlive = cancel
	go service.KeepAlive(keepAliveCtx, e.resolver, e.dialer, "")

	e.bus.setAddrs(proxies.socksAddr, proxies.httpAddr)
	return nil
}

func (e *engine) setupClient(ctx context.Context) error {
	cfg := e.cfg
	e.bus.setPhase(PhaseConnecting, fmt.Sprintf("正在连接 %s / connecting", cfg.ServerAddr()))
	e.log(LevelInfo, "协议：%s，服务器：%s", cfg.Protocol, cfg.ServerAddr())

	switch cfg.Protocol {
	case ProtocolEasyConnect:
		e.client = easyconnectclient.NewClient(easyconnectclient.Options{
			Server: cfg.ServerAddr(),
			Auth: easyconnectclient.AuthOptions{
				Username:      cfg.Username,
				Password:      cfg.Password,
				TOTPSecret:    cfg.TOTPSecret,
				Certificate:   tls.Certificate{},
				GraphCodeFile: "",
			},
			// Non-ZJU deployments without multi-line support must not probe it.
			TestMultiLine: cfg.UserType == UserTypeZJU,
			Resources: easyconnectclient.ResourceOptions{
				Fetch:          true,
				IncludeDomains: !cfg.SkipDomainResource,
			},
			UnderlayDialer:   e.tracker,
			ChallengeHandler: e.challenges,
		})
		ec := e.client.(*easyconnectclient.Client)
		if err := ec.Setup(); err != nil {
			return fmt.Errorf("EasyConnect 登录失败 / login failed: %w", err)
		}
	case ProtocolATrust:
		loginMethod, err := auth.NewLoginMethod(auth.LoginMethodOptions{
			AuthType: cfg.AuthType,
			Username: cfg.Username,
			Password: cfg.Password,
			Phone:    cfg.Phone,
			Domain:   cfg.LoginDomain,
		})
		if err != nil {
			return fmt.Errorf("配置 aTrust 登录方式失败 / cannot configure aTrust login: %w", err)
		}

		clientDataFile := cfg.EffectiveClientDataFile()
		var clientData []byte
		if clientDataFile != "" {
			if data, readErr := os.ReadFile(clientDataFile); readErr == nil {
				clientData = data
				e.log(LevelInfo, "已加载上次登录状态 / restored previous session: %s", clientDataFile)
			}
		}

		at := atrustclient.NewClient(atrustclient.ClientOptions{
			Session:        atrustclient.SessionOptions{Username: cfg.Username},
			UnderlayDialer: e.tracker,
		})

		var saveClientData func([]byte) error
		if clientDataFile != "" {
			saveClientData = func(data []byte) error {
				if err := os.MkdirAll(filepath.Dir(clientDataFile), 0o700); err != nil {
					return err
				}
				return os.WriteFile(clientDataFile, data, 0o600)
			}
		}

		if _, err := at.Setup(atrustclient.SetupOptions{
			ServerAddress:            cfg.Server,
			ServerPort:               cfg.Port,
			LoginMethod:              loginMethod,
			TOTPSecret:               cfg.TOTPSecret,
			ClientData:               clientData,
			ResourceData:             nil,
			BestNodesRefreshInterval: 300 * time.Second,
			SessionRefreshInterval:   1800 * time.Second,
			ChallengeHandler:         e.challenges,
			SaveClientData:           saveClientData,
		}); err != nil {
			return fmt.Errorf("aTrust 登录失败 / login failed: %w", err)
		}
		e.client = at
	default:
		return fmt.Errorf("unsupported protocol %q", cfg.Protocol)
	}

	if e.client == nil {
		return errors.New("VPN 客户端未初始化 / VPN client was not initialised")
	}
	return nil
}

// collectResources gathers server side resource descriptions and, for ZJU
// users on the EasyConnect protocol, merges the built-in ZJU rules exactly
// like upstream zju-connect does.
func (e *engine) collectResources() ([]client.IPResource, client.DomainResources, map[string][]net.IP, error) {
	cfg := e.cfg

	ipResources, err := e.client.IPResources()
	if err != nil && cfg.UserType == UserTypeZJU {
		e.log(LevelWarn, "未获取到 IP 资源 / no IP resources: %v", err)
	}
	domainResources, err := e.client.DomainResources()
	if err != nil && cfg.UserType == UserTypeZJU {
		e.log(LevelWarn, "未获取到域名资源 / no domain resources: %v", err)
	}
	dnsResource, err := e.client.DNSResource()
	if err != nil && cfg.UserType == UserTypeZJU {
		e.log(LevelWarn, "未获取到 DNS 资源 / no DNS resource: %v", err)
	}

	if cfg.Protocol == ProtocolEasyConnect && !cfg.DisableZJUConfig {
		if domainResources == nil {
			domainResources = make(client.DomainResources)
		}
		domainResources["zju.edu.cn"] = []client.DomainResource{{
			PortMin: 1, PortMax: 65535, Protocol: "all",
		}}

		if ipResources == nil {
			ipResources = []client.IPResource{}
		}
		ipResources = append([]client.IPResource{{
			IPMin:    net.ParseIP("10.0.0.0"),
			IPMax:    net.ParseIP("10.255.255.255"),
			PortMin:  1,
			PortMax:  65535,
			Protocol: "all",
		}}, ipResources...)
	}

	if len(ipResources) == 0 && len(domainResources) == 0 && !cfg.ProxyAll {
		e.log(LevelWarn, "服务端未下发任何可代理资源，只有内置规则会走 RVPN / server returned no resources")
	}
	return ipResources, domainResources, dnsResource, nil
}

func (e *engine) setupStack(ipResources []client.IPResource) error {
	if e.cfg.Protocol == ProtocolEasyConnect && e.client.CanUseTCPTunnel() {
		st, err := tcptunnel.NewStack(e.client)
		if err != nil {
			return fmt.Errorf("TCP 隧道初始化失败 / TCP tunnel setup failed: %w", err)
		}
		e.stack = st
		e.log(LevelInfo, "传输模式：TCP 隧道 / TCP tunnel mode")
		return nil
	}

	st, err := gvisor.NewStack(e.client)
	if err != nil {
		return fmt.Errorf("网络栈初始化失败 / network stack setup failed: %w", err)
	}
	e.stack = st
	_ = ipResources
	e.log(LevelInfo, "传输模式：gVisor 用户态网络栈 / gVisor stack mode")
	return nil
}

func (e *engine) setupResolver(ctx context.Context, domainResources client.DomainResources, dnsResource map[string][]net.IP) error {
	cfg := e.cfg

	useRemoteDNS := !cfg.DisableRemoteDNS
	remoteDNSServer := cfg.RemoteDNSServer
	policyDNSServers, _ := e.client.DNSServers()
	if useRemoteDNS && remoteDNSServer == "auto" {
		resolved, err := e.client.DNSServer()
		if err != nil {
			useRemoteDNS = false
			remoteDNSServer = "10.10.0.21"
			e.log(LevelWarn, "服务端未下发 DNS 服务器，改用本地 DNS 解析 / no DNS server from server, using local DNS")
		} else {
			remoteDNSServer = resolved
			e.log(LevelInfo, "使用服务端 DNS：%s / using remote DNS", remoteDNSServer)
		}
	}

	secondaryDNSServer := cfg.SecondaryDNSServer
	if secondaryDNSServer == "auto" {
		secondaryDNSServer = "114.114.114.114"
		if len(policyDNSServers) > 1 {
			secondaryDNSServer = policyDNSServers[1]
		}
	}

	resolver := resolve.NewResolver(
		e.stack,
		remoteDNSServer,
		secondaryDNSServer,
		cfg.DNSTTLSeconds,
		domainResources,
		dnsResource,
		useRemoteDNS,
	)
	e.resolver = resolver

	localResolver := service.NewDnsServer(resolver, []string{remoteDNSServer, secondaryDNSServer})
	e.stack.SetupResolve(localResolver)
	e.stack.SetupIPPool(resolver.IPPool)

	go e.stack.Run()
	return nil
}

// close releases every resource in reverse order of creation. It is safe to
// call on a partially initialised engine and more than once.
func (e *engine) close() {
	e.closeOnce.Do(e.closeResources)
}

func (e *engine) closeResources() {
	if e.keepAlive != nil {
		e.keepAlive()
		e.keepAlive = nil
	}
	if e.proxies != nil {
		e.proxies.Close()
		e.proxies = nil
	}
	if e.resolver != nil {
		e.resolver.Close()
		e.resolver = nil
	}
	if e.client != nil {
		// The Client interface has no Close method; both concrete clients
		// implement one.
		if closer, ok := e.client.(interface{ Close() }); ok {
			closer.Close()
		}
		e.client = nil
	}
	// The upstream clients keep their HTTP transports (and therefore their
	// keep-alive sockets to the VPN server) open, so the sockets are closed
	// from here instead.
	if e.tracker != nil {
		e.tracker.CloseAll()
		e.tracker = nil
	}
	if e.underlay != nil {
		_ = e.underlay.Close()
		e.underlay = nil
	}
	e.dialer = nil
	e.stack = nil
}
