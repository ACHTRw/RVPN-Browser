package rvpn

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestNormalizeZJUDefaults(t *testing.T) {
	cases := []struct {
		protocol string
		server   string
		domain   string
	}{
		{"", DefaultZJUServerATrust, DefaultZJULoginDomain},
		{ProtocolATrust, DefaultZJUServerATrust, DefaultZJULoginDomain},
		{ProtocolEasyConnect, DefaultZJUServerEasyConnect, ""},
	}
	for _, tc := range cases {
		cfg := Config{UserType: UserTypeZJU, Protocol: tc.protocol, Username: "u", Password: "p"}
		if err := cfg.Normalize(); err != nil {
			t.Fatalf("Normalize(%q) error: %v", tc.protocol, err)
		}
		if cfg.Server != tc.server {
			t.Errorf("protocol %q: server = %q, want %q", tc.protocol, cfg.Server, tc.server)
		}
		if cfg.LoginDomain != tc.domain {
			t.Errorf("protocol %q: loginDomain = %q, want %q", tc.protocol, cfg.LoginDomain, tc.domain)
		}
		if cfg.Port != DefaultZJUPort {
			t.Errorf("protocol %q: port = %d, want %d", tc.protocol, cfg.Port, DefaultZJUPort)
		}
		if cfg.SocksBind != "127.0.0.1:0" || cfg.HTTPBind != "127.0.0.1:0" {
			t.Errorf("protocol %q: unexpected binds %q/%q", tc.protocol, cfg.SocksBind, cfg.HTTPBind)
		}
	}
}

func TestNormalizeNonZJU(t *testing.T) {
	cfg := Config{UserType: UserTypeOther, Username: "u", Password: "p"}
	if err := cfg.Normalize(); err == nil {
		t.Fatal("non-ZJU user without a server must be rejected")
	}

	cfg = Config{UserType: UserTypeOther, Server: "vpn.example.edu", Port: 443,
		Username: "u", Password: "p", LoginDomain: "Radius"}
	if err := cfg.Normalize(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !cfg.DisableZJUConfig || !cfg.SkipDomainResource {
		t.Error("non-ZJU users must not use the built-in ZJU rules")
	}
	if cfg.Protocol != ProtocolATrust {
		t.Errorf("default protocol = %q, want atrust", cfg.Protocol)
	}
}

func TestNormalizeRejects(t *testing.T) {
	base := func() Config {
		return Config{UserType: UserTypeZJU, Username: "u", Password: "p"}
	}

	tests := []struct {
		name   string
		mutate func(*Config)
		want   string
	}{
		{"bad userType", func(c *Config) { c.UserType = "nobody" }, "userType"},
		{"bad protocol", func(c *Config) { c.Protocol = "ipsec" }, "protocol"},
		{"bad port", func(c *Config) { c.Port = 70000 }, "port"},
		{"missing credentials", func(c *Config) { c.Password = "" }, "password"},
		{"sms without phone", func(c *Config) { c.AuthType = "auth/smsCheckCode" }, "phone"},
		{"bad authType", func(c *Config) { c.AuthType = "auth/magic" }, "authType"},
		{"public socks bind", func(c *Config) { c.SocksBind = "0.0.0.0:1080" }, "loopback"},
		{"bare port bind", func(c *Config) { c.SocksBind = ":1080" }, "loopback"},
		{"public http bind", func(c *Config) { c.HTTPBind = "192.168.1.5:1081" }, "loopback"},
		{"bad dial proxy", func(c *Config) { c.DialDirectProxy = "ftp://x" }, "dialDirectProxy"},
	}
	for _, tc := range tests {
		cfg := base()
		tc.mutate(&cfg)
		err := cfg.Normalize()
		if err == nil {
			t.Errorf("%s: expected an error", tc.name)
			continue
		}
		if !strings.Contains(strings.ToLower(err.Error()), strings.ToLower(tc.want)) {
			t.Errorf("%s: error %q does not mention %q", tc.name, err, tc.want)
		}
	}
}

func TestNormalizeAllowsLoopbackVariants(t *testing.T) {
	for _, bind := range []string{"127.0.0.1:0", "localhost:1080", "[::1]:1081", "127.0.0.5:9000"} {
		cfg := Config{UserType: UserTypeZJU, Username: "u", Password: "p", SocksBind: bind}
		if err := cfg.Normalize(); err != nil {
			t.Errorf("bind %q should be accepted: %v", bind, err)
		}
	}
}

func TestSummaryHidesCredentials(t *testing.T) {
	cfg := Config{UserType: UserTypeZJU, Username: "3200101234", Password: "s3cr3t-password"}
	if err := cfg.Normalize(); err != nil {
		t.Fatal(err)
	}
	summary := cfg.Summary()
	if strings.Contains(summary, "s3cr3t-password") {
		t.Fatalf("summary leaks the password: %s", summary)
	}
	if strings.Contains(summary, "3200101234") {
		t.Fatalf("summary leaks the full username: %s", summary)
	}
}

func TestClientDataFileDefault(t *testing.T) {
	cfg := Config{}
	if got := cfg.EffectiveClientDataFile(); !strings.HasSuffix(got, "rvpn-browser/client_data.json") {
		t.Errorf("unexpected default client data file: %q", got)
	}
	empty := ""
	cfg.ClientDataFile = &empty
	if got := cfg.EffectiveClientDataFile(); got != "" {
		t.Errorf("explicit empty path must disable persistence, got %q", got)
	}
	path := "/tmp/x.json"
	cfg.ClientDataFile = &path
	if got := cfg.EffectiveClientDataFile(); got != path {
		t.Errorf("explicit path ignored: %q", got)
	}
}

func TestConfigJSONRoundTrip(t *testing.T) {
	// The front-ends send camelCase keys; make sure they are honoured.
	raw := `{"userType":"zju","protocol":"atrust","username":"u","password":"p",
	         "socksBind":"127.0.0.1:0","httpBind":"127.0.0.1:0","debug":true,
	         "dnsTTLSeconds":120,"remoteDNSServer":"auto","secondaryDNSServer":"auto"}`
	var cfg Config
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatal(err)
	}
	if err := cfg.Normalize(); err != nil {
		t.Fatal(err)
	}
	if !cfg.Debug || cfg.DNSTTLSeconds != 120 {
		t.Fatalf("unexpected config: %+v", cfg)
	}
	if cfg.ClientDataFile != nil {
		t.Error("clientDataFile must stay nil when the front-end does not send it")
	}
}

func TestRedaction(t *testing.T) {
	clearSecrets()
	defer clearSecrets()
	registerSecrets("hunter2password", "ABCDEFGH")
	got := redact("login failed for hunter2password with otp ABCDEFGH")
	if strings.Contains(got, "hunter2password") || strings.Contains(got, "ABCDEFGH") {
		t.Fatalf("secrets not redacted: %s", got)
	}
	// Short values are not registered to avoid destroying every log line.
	registerSecrets("ab")
	if redact("ab") != "ab" {
		t.Error("short secrets must not be redacted")
	}
}

func TestClassifyError(t *testing.T) {
	cases := map[string]string{
		"dial tcp: lookup vpn.zju.edu.cn: no such host": CodeNetworkError,
		"connection refused":                            CodeNetworkError,
		"Invalid username or password!":                 CodeAuthFailed,
		"something odd happened":                        CodeInternal,
	}
	for msg, want := range cases {
		if got := classifyError(errString(msg)); got != want {
			t.Errorf("classifyError(%q) = %q, want %q", msg, got, want)
		}
	}
}

type errString string

func (e errString) Error() string { return string(e) }
