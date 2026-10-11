// Package rvpn implements the RVPN Browser core engine: a browser-oriented
// wrapper around the ZJU RVPN protocol implementations provided by
// github.com/mythologyli/zju-connect.
//
// The package exposes a small, JSON-driven, platform neutral API (see
// docs/PROTOCOL.md) so that the desktop browser extension (through Chrome
// native messaging) and the Android application (through a gomobile binding)
// share exactly the same engine behaviour.
package rvpn

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// Version is the RVPN Browser core version.
const Version = "1.0.0"

// Protocol identifiers.
const (
	ProtocolATrust      = "atrust"
	ProtocolEasyConnect = "easyconnect"
)

// User types.
const (
	UserTypeZJU   = "zju"
	UserTypeOther = "other"
)

// Default ZJU servers (see upstream zju-connect: atrust uses vpn.zju.edu.cn,
// easyconnect uses rvpn.zju.edu.cn).
const (
	DefaultZJUServerATrust      = "vpn.zju.edu.cn"
	DefaultZJUServerEasyConnect = "rvpn.zju.edu.cn"
	DefaultZJULoginDomain       = "Radius"
	DefaultZJUPort              = 443
)

// Config is the RVPN session configuration. Field names and JSON tags are part
// of the frozen protocol described in docs/PROTOCOL.md.
type Config struct {
	UserType string `json:"userType"`
	Protocol string `json:"protocol"`

	Server string `json:"server"`
	Port   int    `json:"port"`

	Username   string `json:"username"`
	Password   string `json:"password"`
	LoginDomain string `json:"loginDomain"`
	AuthType   string `json:"authType"`
	Phone      string `json:"phone"`
	TOTPSecret string `json:"totpSecret"`

	// ClientDataFile persists the aTrust session. nil means "use the platform
	// default", an explicit empty string means "do not persist".
	ClientDataFile *string `json:"clientDataFile"`

	SocksBind string `json:"socksBind"`
	HTTPBind  string `json:"httpBind"`

	ProxyAll           bool   `json:"proxyAll"`
	DisableZJUConfig   bool   `json:"disableZJUConfig"`
	SkipDomainResource bool   `json:"skipDomainResource"`
	DisableRemoteDNS   bool   `json:"disableRemoteDNS"`
	RemoteDNSServer    string `json:"remoteDNSServer"`
	SecondaryDNSServer string `json:"secondaryDNSServer"`
	DNSTTLSeconds      uint64 `json:"dnsTTLSeconds"`

	// DialDirectProxy optionally routes non-RVPN traffic through another local
	// proxy (format: http://127.0.0.1:7890 or socks://127.0.0.1:7891).
	DialDirectProxy string `json:"dialDirectProxy"`

	Debug bool `json:"debug"`
}

// Normalize validates the configuration and fills in defaults. It returns a
// user-presentable error for anything that cannot be fixed up automatically.
func (c *Config) Normalize() error {
	if c.UserType == "" {
		c.UserType = UserTypeZJU
	}
	switch c.UserType {
	case UserTypeZJU, UserTypeOther:
	default:
		return fmt.Errorf("unknown userType %q (expected zju or other)", c.UserType)
	}

	if c.Protocol == "" {
		// ZJU users default to aTrust (L3), which is the protocol the official
		// ZJU client uses.
		c.Protocol = ProtocolATrust
	}
	switch c.Protocol {
	case ProtocolATrust, ProtocolEasyConnect:
	default:
		return fmt.Errorf("unknown protocol %q (expected atrust or easyconnect)", c.Protocol)
	}

	if c.UserType == UserTypeZJU {
		if c.Server == "" {
			if c.Protocol == ProtocolATrust {
				c.Server = DefaultZJUServerATrust
			} else {
				c.Server = DefaultZJUServerEasyConnect
			}
		}
		// Keep the ZJU convenience behaviour of upstream: the credentials of a
		// ZJU account are validated by the "Radius" login domain.
		if c.LoginDomain == "" && c.Protocol == ProtocolATrust {
			c.LoginDomain = DefaultZJULoginDomain
		}
	} else {
		if strings.TrimSpace(c.Server) == "" {
			return errors.New("非 ZJU 用户必须填写 VPN 服务器地址 / server address is required for non-ZJU users")
		}
		// Non-ZJU servers do not know about ZJU resources: never inject the
		// built-in ZJU rules and do not rely on ZJU specific server config.
		c.DisableZJUConfig = true
		c.SkipDomainResource = true
	}

	if c.Port == 0 {
		c.Port = DefaultZJUPort
	}
	if c.Port < 1 || c.Port > 65535 {
		return fmt.Errorf("invalid server port %d", c.Port)
	}

	if c.AuthType == "" && c.Protocol == ProtocolATrust {
		switch {
		case c.Username != "" && c.Password != "":
			c.AuthType = "auth/psw"
		case c.Phone != "":
			c.AuthType = "auth/smsCheckCode"
		}
	}

	switch c.AuthType {
	case "", "auth/psw", "auth/cas", "auth/smsCheckCode", "auth/httpsOauth2":
	default:
		return fmt.Errorf("unsupported authType %q", c.AuthType)
	}

	if c.Protocol == ProtocolATrust {
		if c.AuthType == "auth/smsCheckCode" {
			if strings.TrimSpace(c.Phone) == "" {
				return errors.New("短信认证需要填写手机号 / phone number is required for SMS authentication")
			}
		} else if c.AuthType == "auth/psw" || c.AuthType == "" {
			if c.Username == "" || c.Password == "" {
				return errors.New("请填写账号和密码 / username and password are required")
			}
		}
	} else {
		if c.Username == "" || c.Password == "" {
			return errors.New("请填写账号和密码 / username and password are required")
		}
	}

	if c.SocksBind == "" {
		c.SocksBind = "127.0.0.1:0"
	}
	if err := checkLoopbackBind("socksBind", c.SocksBind); err != nil {
		return err
	}
	if c.HTTPBind == "" {
		c.HTTPBind = "127.0.0.1:0"
	}
	if c.HTTPBind != "-" {
		if err := checkLoopbackBind("httpBind", c.HTTPBind); err != nil {
			return err
		}
	}

	if c.RemoteDNSServer == "" {
		c.RemoteDNSServer = "auto"
	}
	if c.SecondaryDNSServer == "" {
		c.SecondaryDNSServer = "auto"
	}
	if c.DNSTTLSeconds == 0 {
		c.DNSTTLSeconds = 3600
	}
	if c.DialDirectProxy != "" {
		switch {
		case strings.HasPrefix(c.DialDirectProxy, "http://"),
			strings.HasPrefix(c.DialDirectProxy, "socks://"):
		default:
			return fmt.Errorf("unsupported dialDirectProxy %q", c.DialDirectProxy)
		}
	}
	return nil
}

func checkLoopbackBind(field, addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("invalid %s %q: %v", field, addr, err)
	}
	switch host {
	case "127.0.0.1", "localhost", "::1":
		return nil
	case "":
		// A bare ":port" would expose the proxy to the whole network.
		return fmt.Errorf("%s must bind to the loopback address, got %q", field, addr)
	default:
		ip := net.ParseIP(host)
		if ip != nil && ip.IsLoopback() {
			return nil
		}
		return fmt.Errorf("%s must bind to the loopback address, got %q", field, addr)
	}
}

// EffectiveClientDataFile resolves the aTrust session file location.
func (c *Config) EffectiveClientDataFile() string {
	if c.ClientDataFile != nil {
		return *c.ClientDataFile
	}
	dir, err := os.UserConfigDir()
	if err != nil || dir == "" {
		return ""
	}
	return filepath.Join(dir, "rvpn-browser", "client_data.json")
}

// ServerAddr returns host:port of the configured VPN server.
func (c *Config) ServerAddr() string {
	return net.JoinHostPort(c.Server, fmt.Sprintf("%d", c.Port))
}

// Summary returns a log-safe description of the configuration. It never
// contains credentials.
func (c *Config) Summary() string {
	return fmt.Sprintf("userType=%s protocol=%s server=%s username=%s loginDomain=%s authType=%s debug=%v proxyAll=%v",
		c.UserType, c.Protocol, c.ServerAddr(), maskUser(c.Username), c.LoginDomain, c.AuthType, c.Debug, c.ProxyAll)
}

func maskUser(user string) string {
	if user == "" {
		return "(empty)"
	}
	r := []rune(user)
	if len(r) <= 2 {
		return "***"
	}
	return string(r[:1]) + "***" + string(r[len(r)-1:])
}

// defaultTimeout is used for the overall connect handshake.
const defaultConnectTimeout = 90 * time.Second

func platformName() string {
	switch runtime.GOOS {
	case "linux", "android", "darwin", "windows":
		return runtime.GOOS
	default:
		return runtime.GOOS
	}
}
