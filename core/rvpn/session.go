package rvpn

import (
	"context"
	"fmt"
	"io"
	stdlog "log"
	"sync"
	"time"

	"github.com/mythologyli/zju-connect/client/authchallenge"
)

// Session owns one RVPN engine instance plus the event stream that front-ends
// poll. A Session is long lived: it survives start/stop cycles of the engine.
type Session struct {
	bus        *eventBus
	challenges *challengeManager
	sink       *logSink

	mu      sync.Mutex
	engine  *engine
	cancel  context.CancelFunc
	done    chan struct{}
	running bool
	stopped bool

	restoreLog func()
}

// NewSession creates a session and redirects the standard library logger used
// by zju-connect into the session event stream. This must happen before any
// protocol code runs: stdout belongs to the native messaging framing.
func NewSession() *Session {
	bus := newEventBus()
	sink := &logSink{bus: bus}
	prevOut := stdlog.Writer()
	stdlog.SetOutput(sink)
	stdlog.SetFlags(stdlog.LstdFlags)

	s := &Session{
		bus:        bus,
		challenges: newChallengeManager(bus),
		sink:       sink,
		restoreLog: func() { stdlog.SetOutput(prevOut) },
	}
	return s
}

// Close shuts the session down for good.
func (s *Session) Close() {
	s.Stop()
	s.challenges.Close()
	if s.restoreLog != nil {
		s.restoreLog()
		s.restoreLog = nil
	}
	clearSecrets()
}

// LogSink exposes the writer that captures upstream logs.
func (s *Session) LogSink() io.Writer { return s.sink }

// Running reports whether an engine is currently active.
func (s *Session) Running() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

// Start validates the configuration and starts connecting in the background.
// Configuration problems are reported synchronously; everything else is
// reported through the event stream.
func (s *Session) Start(cfg Config) error {
	if err := cfg.Normalize(); err != nil {
		return &APIError{Code: CodeInvalidConfig, Err: err}
	}
	if cfg.Debug {
		debugEnabled.Store(true)
	} else {
		debugEnabled.Store(false)
	}

	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return &APIError{Code: CodeAlreadyRunning, Err: fmt.Errorf("RVPN 已在运行 / RVPN is already running")}
	}
	if s.stopped {
		s.mu.Unlock()
		return &APIError{Code: CodeInternal, Err: fmt.Errorf("session closed")}
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	s.running = true
	s.cancel = cancel
	s.done = done
	s.mu.Unlock()

	s.challenges.reset()
	s.bus.resetState(&cfg)
	s.bus.log(LevelInfo, "RVPN Browser 核心 v%s 启动 / core starting", Version)

	eng := newEngine(&cfg, s.bus, s.challenges, s.sink)
	s.mu.Lock()
	s.engine = eng
	s.mu.Unlock()

	go func() {
		defer close(done)

		connectCtx, cancelConnect := context.WithTimeout(ctx, defaultConnectTimeout)
		err := eng.connect(connectCtx)
		cancelConnect()

		if err != nil {
			eng.close()
			if ctx.Err() != nil {
				// Stop was requested while connecting.
				return
			}
			s.mu.Lock()
			s.running = false
			s.engine = nil
			s.mu.Unlock()
			s.bus.setError(publicError(err))
			return
		}

		s.bus.setPhase(PhaseConnected, fmt.Sprintf("已连接 %s / connected", cfg.ServerAddr()))
		s.bus.setConnectedAt(time.Now().UnixMilli())
		s.bus.log(LevelInfo, "RVPN 连接成功 / RVPN connected")

		<-ctx.Done()
	}()

	return nil
}

// Stop tears the engine down. It is idempotent.
func (s *Session) Stop() {
	s.mu.Lock()
	if !s.running && s.cancel == nil {
		s.mu.Unlock()
		return
	}
	cancel := s.cancel
	done := s.done
	eng := s.engine
	s.cancel = nil
	s.done = nil
	s.engine = nil
	s.running = false
	s.mu.Unlock()

	if eng != nil {
		s.bus.setPhase(PhaseStopping, "正在断开 / disconnecting")
	}
	s.challenges.Close()
	if cancel != nil {
		cancel()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			s.bus.log(LevelWarn, "断开超时，强制释放资源 / stop timed out, forcing cleanup")
		}
	}
	if eng != nil {
		eng.close()
	}
	s.bus.resetToIdle()
	s.bus.log(LevelInfo, "RVPN 已停止 / RVPN stopped")
	clearSecrets()
}

// StatusData is the reply of the "status" command.
type StatusData struct {
	State        State       `json:"state"`
	Events       []Event     `json:"events"`
	NextSince    int64       `json:"nextSince"`
	Pending      []Challenge `json:"pending"`
	Running      bool        `json:"running"`
	Capabilities []string    `json:"capabilities"`
}

// Status returns the current state plus every event newer than since.
func (s *Session) Status(since int64) StatusData {
	events, next := s.bus.since(since)
	if events == nil {
		events = []Event{}
	}
	// Bound the reply size so a native messaging frame always fits.
	if len(events) > maxEventsPerPoll {
		events = events[len(events)-maxEventsPerPoll:]
	}
	return StatusData{
		State:        s.bus.State(),
		Events:       events,
		NextSince:    next,
		Pending:      s.challenges.Pending(),
		Running:      s.Running(),
		Capabilities: Capabilities(),
	}
}

// AnswerRequest is the payload of the "answer" command.
type AnswerRequest struct {
	ChallengeID       string `json:"challengeId"`
	Code              string `json:"code"`
	Points            []struct {
		X int `json:"x"`
		Y int `json:"y"`
	} `json:"points"`
	Width             int  `json:"width"`
	Height            int  `json:"height"`
	SkipSecondaryAuth bool `json:"skipSecondaryAuth"`
}

// Answer delivers a user response to a pending challenge.
func (s *Session) Answer(req AnswerRequest) error {
	if req.ChallengeID == "" {
		return &APIError{Code: CodeInvalidConfig, Err: fmt.Errorf("challengeId is required")}
	}
	ans := challengeAnswer{
		code:              req.Code,
		width:             req.Width,
		height:            req.Height,
		skipSecondaryAuth: req.SkipSecondaryAuth,
	}
	for _, p := range req.Points {
		ans.points = append(ans.points, authchallenge.Point{X: p.X, Y: p.Y})
	}
	if err := s.challenges.Answer(req.ChallengeID, ans); err != nil {
		code := CodeInternal
		if err == ErrNoSuchChallenge {
			code = CodeNoSuchChallenge
		}
		return &APIError{Code: code, Err: err}
	}
	return nil
}

// Capabilities lists the optional features this core build supports.
func Capabilities() []string {
	return []string{
		"atrust", "easyconnect", "zju", "customServer", "captcha", "clickCaptcha",
		"sms", "totp", "authInfo", "selftest", "clientData",
	}
}
