package rvpn

import (
	"encoding/json"
	"fmt"
	"net"
	"runtime"
	"runtime/debug"
	"strings"
	"time"

	atrustclient "github.com/mythologyli/zju-connect/client/atrust"
	zlog "github.com/mythologyli/zju-connect/log"
)

// minimum version of the upstream protocol library; reported when build info
// is unavailable.
const fallbackZJUConnectVersion = "v1.3.2-0.20261008095431-c19eb4ed34b9"

// Request is the envelope of every command (docs/PROTOCOL.md §1).
type Request struct {
	ID     json.RawMessage `json:"id"`
	Cmd    string          `json:"cmd"`
	Config *Config         `json:"config"`
	Since  *int64          `json:"since"`

	ChallengeID       string `json:"challengeId"`
	Code              string `json:"code"`
	Points            []struct {
		X int `json:"x"`
		Y int `json:"y"`
	} `json:"points"`
	Width             int  `json:"width"`
	Height            int  `json:"height"`
	SkipSecondaryAuth bool `json:"skipSecondaryAuth"`

	Server string `json:"server"`
	Port   int    `json:"port"`
	Debug  *bool  `json:"debug"`
}

// Response is the envelope of every reply.
type Response struct {
	ID    json.RawMessage `json:"id,omitempty"`
	OK    bool            `json:"ok"`
	Error string          `json:"error,omitempty"`
	Code  string          `json:"code,omitempty"`
	Data  any             `json:"data,omitempty"`
}

// HelloData describes the core to the front-end.
type HelloData struct {
	Version           string   `json:"version"`
	CoreVersion       string   `json:"coreVersion"`
	ZJUConnectVersion string   `json:"zjuConnectVersion"`
	Platform          string   `json:"platform"`
	Arch              string   `json:"arch"`
	Capabilities      []string `json:"capabilities"`
}

// SelfTestData is the reply of the "selftest" command.
type SelfTestData struct {
	Reachable bool   `json:"reachable"`
	LatencyMs int64  `json:"latencyMs"`
	Server    string `json:"server"`
	Error     string `json:"error"`
}

// Handler dispatches protocol commands onto a Session.
type Handler struct {
	session  *Session
	shutdown chan struct{}
	closed   bool
}

// NewHandler creates a handler with a fresh session.
func NewHandler() *Handler {
	return &Handler{session: NewSession(), shutdown: make(chan struct{}, 1)}
}

// ShutdownRequested is closed when the front-end asked the process to exit.
func (h *Handler) ShutdownRequested() <-chan struct{} { return h.shutdown }

// Session exposes the underlying session (used by tests).
func (h *Handler) Session() *Session { return h.session }

// HandleRaw parses, dispatches and serialises one request. It never panics:
// an unexpected failure is turned into an INTERNAL error response.
func (h *Handler) HandleRaw(raw []byte) (out []byte) {
	defer func() {
		if r := recover(); r != nil {
			debugLog(h.session.bus, "panic while handling request: %v\n%s", r, debug.Stack())
			h.session.bus.log(LevelError, "内部错误 / internal error: %v", r)
			out = mustMarshal(Response{OK: false, Error: fmt.Sprintf("internal error: %v", r), Code: CodeInternal})
		}
	}()

	var req Request
	if err := json.Unmarshal(raw, &req); err != nil {
		return mustMarshal(Response{OK: false, Error: "invalid JSON request: " + err.Error(), Code: CodeInvalidConfig})
	}
	resp := h.dispatch(&req)
	return mustMarshal(resp)
}

func (h *Handler) dispatch(req *Request) Response {
	resp := Response{ID: req.ID}

	switch strings.ToLower(strings.TrimSpace(req.Cmd)) {
	case "hello":
		resp.OK = true
		resp.Data = HelloData{
			Version:           Version,
			CoreVersion:       Version,
			ZJUConnectVersion: zjuConnectVersion(),
			Platform:          platformName(),
			Arch:              runtime.GOARCH,
			Capabilities:      Capabilities(),
		}

	case "start":
		if req.Config == nil {
			return fail(req, CodeInvalidConfig, fmt.Errorf("missing config"))
		}
		cfg := *req.Config
		if cfg.Debug {
			zlog.EnableDebug()
			SetDebug(true)
		} else {
			zlog.DisableDebug()
			SetDebug(false)
		}
		if err := h.session.Start(cfg); err != nil {
			return failErr(req, err)
		}
		resp.OK = true
		resp.Data = map[string]any{"state": h.session.bus.State()}

	case "stop":
		h.session.Stop()
		resp.OK = true
		resp.Data = map[string]any{"state": h.session.bus.State()}

	case "status":
		var since int64
		if req.Since != nil {
			since = *req.Since
		}
		resp.OK = true
		resp.Data = h.session.Status(since)

	case "answer":
		if err := h.session.Answer(AnswerRequest{
			ChallengeID:       req.ChallengeID,
			Code:              req.Code,
			Points:            req.Points,
			Width:             req.Width,
			Height:            req.Height,
			SkipSecondaryAuth: req.SkipSecondaryAuth,
		}); err != nil {
			return failErr(req, err)
		}
		resp.OK = true
		resp.Data = map[string]any{"accepted": true}

	case "authinfo", "auth_info":
		data, err := h.authInfo(req)
		if err != nil {
			return failErr(req, err)
		}
		resp.OK = true
		resp.Data = data

	case "selftest":
		resp.OK = true
		resp.Data = selfTest(req.Server, req.Port)

	case "setdebug":
		if req.Debug == nil {
			return fail(req, CodeInvalidConfig, fmt.Errorf("missing debug flag"))
		}
		SetDebug(*req.Debug)
		if *req.Debug {
			zlog.EnableDebug()
		} else {
			zlog.DisableDebug()
		}
		resp.OK = true
		resp.Data = map[string]any{"debug": *req.Debug}

	case "shutdown":
		h.session.Stop()
		select {
		case h.shutdown <- struct{}{}:
		default:
		}
		resp.OK = true
		resp.Data = map[string]any{}

	default:
		return fail(req, CodeInvalidConfig, fmt.Errorf("unknown command: %s", req.Cmd))
	}

	return resp
}

// authInfo probes an aTrust server for its supported login domains and
// authentication methods (needed by non-ZJU users).
func (h *Handler) authInfo(req *Request) (any, error) {
	server := strings.TrimSpace(req.Server)
	if server == "" {
		server = DefaultZJUServerATrust
	}
	port := req.Port
	if port == 0 {
		port = DefaultZJUPort
	}
	if port < 1 || port > 65535 {
		return nil, &APIError{Code: CodeInvalidConfig, Err: fmt.Errorf("invalid port %d", port)}
	}

	h.session.bus.log(LevelInfo, "正在探测 %s:%d 的登录方式 / probing authentication methods", server, port)
	info, err := atrustclient.GetAuthInfoList(server, port, "", false, "", "")
	if err != nil {
		return nil, &APIError{Code: classifyError(err), Err: fmt.Errorf("探测登录方式失败 / cannot fetch auth info: %w", err)}
	}
	list := make([]map[string]any, 0, len(info))
	for _, item := range info {
		entry := map[string]any{}
		raw, marshalErr := json.Marshal(item)
		if marshalErr != nil {
			continue
		}
		if unmarshalErr := json.Unmarshal(raw, &entry); unmarshalErr != nil {
			continue
		}
		list = append(list, entry)
	}
	return map[string]any{"list": list}, nil
}

func selfTest(server string, port int) SelfTestData {
	if strings.TrimSpace(server) == "" {
		server = DefaultZJUServerATrust
	}
	if port == 0 {
		port = DefaultZJUPort
	}
	addr := net.JoinHostPort(server, fmt.Sprintf("%d", port))
	start := time.Now()
	conn, err := net.DialTimeout("tcp", addr, reachabilityTimeout)
	latency := time.Since(start).Milliseconds()
	if err != nil {
		return SelfTestData{Reachable: false, LatencyMs: latency, Server: addr, Error: publicError(err)}
	}
	_ = conn.Close()
	return SelfTestData{Reachable: true, LatencyMs: latency, Server: addr}
}

func fail(req *Request, code string, err error) Response {
	return Response{ID: req.ID, OK: false, Error: publicError(err), Code: code}
}

func failErr(req *Request, err error) Response {
	return Response{ID: req.ID, OK: false, Error: publicError(err), Code: ErrorCodeOf(err)}
}

func mustMarshal(v any) []byte {
	data, err := json.Marshal(v)
	if err != nil {
		return []byte(`{"ok":false,"error":"cannot serialise response","code":"INTERNAL"}`)
	}
	return data
}

// zjuConnectVersion reports the pinned upstream protocol library version that
// this binary was built against.
func zjuConnectVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return fallbackZJUConnectVersion
	}
	for _, dep := range info.Deps {
		if dep.Path == "github.com/mythologyli/zju-connect" {
			if dep.Replace != nil && dep.Replace.Version != "" {
				return dep.Replace.Version
			}
			if dep.Version != "" {
				return dep.Version
			}
		}
	}
	return fallbackZJUConnectVersion
}
