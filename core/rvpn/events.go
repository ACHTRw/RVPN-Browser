package rvpn

import (
	"strings"
	"sync"
	"time"
)

// Session phases (see docs/PROTOCOL.md).
const (
	PhaseIdle           = "idle"
	PhaseConnecting     = "connecting"
	PhaseAuthenticating = "authenticating"
	PhaseConnected      = "connected"
	PhaseError          = "error"
	PhaseStopping       = "stopping"
)

// Event kinds.
const (
	KindLog           = "log"
	KindState         = "state"
	KindChallenge     = "challenge"
	KindChallengeDone = "challengeDone"
	KindError         = "error"
)

// Log levels.
const (
	LevelDebug = "debug"
	LevelInfo  = "info"
	LevelWarn  = "warn"
	LevelError = "error"
)

// Challenge types.
const (
	ChallengeText          = "text"
	ChallengeClickCode     = "clickCode"
	ChallengeSMSCode       = "smsCode"
	ChallengeTOTP          = "totp"
	ChallengeRadiusCode    = "radiusCode"
	ChallengeSecondaryAuth = "secondaryAuth"
	ChallengeExternalLogin = "externalLogin"
)

const eventBufferSize = 2000

// Event is a single item of the incremental event stream.
type Event struct {
	Seq       int64      `json:"seq"`
	TS        int64      `json:"ts"`
	Kind      string     `json:"kind"`
	Level     string     `json:"level"`
	Message   string     `json:"message"`
	Challenge *Challenge `json:"challenge,omitempty"`
}

// Challenge is a pending interactive authentication step.
type Challenge struct {
	ID                   string `json:"id"`
	Type                 string `json:"type"`
	Message              string `json:"message"`
	ImageDataURL         string `json:"imageDataUrl,omitempty"`
	CanSkipSecondaryAuth bool   `json:"canSkipSecondaryAuth,omitempty"`
	LoginURL             string `json:"loginUrl,omitempty"`
	Deadline             int64  `json:"deadline"`
	CreatedAt            int64  `json:"createdAt"`
}

// State is the current session state reported to the front-end.
type State struct {
	Phase       string `json:"phase"`
	Message     string `json:"message"`
	Since       int64  `json:"since"`
	Protocol    string `json:"protocol"`
	UserType    string `json:"userType"`
	Server      string `json:"server"`
	SocksAddr   string `json:"socksAddr"`
	HTTPAddr    string `json:"httpAddr"`
	ClientIP    string `json:"clientIP"`
	ConnectedAt int64  `json:"connectedAt"`
	LastError   string `json:"lastError"`
}

// eventBus is a bounded, monotonic event log. Front-ends poll it with a
// sequence number, so no event is ever lost between two polls unless the
// caller lags behind more than eventBufferSize entries.
type eventBus struct {
	mu     sync.Mutex
	seq    int64
	events []Event
	// subscribers receive every event as it is published (used by the
	// challenge watcher, which must not depend on polling).
	subs map[int]chan Event
	next int

	state State
}

func newEventBus() *eventBus {
	return &eventBus{subs: make(map[int]chan Event), state: State{Phase: PhaseIdle, Since: time.Now().UnixMilli()}}
}

// resetState prepares the state for a new session.
func (b *eventBus) resetState(cfg *Config) {
	b.mu.Lock()
	b.state = State{
		Phase:    PhaseIdle,
		Message:  "",
		Since:    time.Now().UnixMilli(),
		Protocol: cfg.Protocol,
		UserType: cfg.UserType,
		Server:   cfg.ServerAddr(),
	}
	b.mu.Unlock()
}

// State returns a copy of the current state.
func (b *eventBus) State() State {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state
}

func (b *eventBus) setPhase(phase, message string) {
	b.mu.Lock()
	if b.state.Phase != phase {
		b.state.Since = time.Now().UnixMilli()
	}
	b.state.Phase = phase
	b.state.Message = message
	state := b.state
	b.mu.Unlock()
	b.publish(Event{Kind: KindState, Level: LevelInfo, Message: message})
	_ = state
}

func (b *eventBus) setError(message string) {
	b.mu.Lock()
	b.state.Phase = PhaseError
	b.state.Message = message
	b.state.LastError = message
	b.state.Since = time.Now().UnixMilli()
	b.state.SocksAddr = ""
	b.state.HTTPAddr = ""
	b.state.ClientIP = ""
	b.state.ConnectedAt = 0
	b.mu.Unlock()
	b.publish(Event{Kind: KindError, Level: LevelError, Message: message})
}

func (b *eventBus) setAddrs(socksAddr, httpAddr string) {
	b.mu.Lock()
	b.state.SocksAddr = socksAddr
	b.state.HTTPAddr = httpAddr
	b.mu.Unlock()
}

func (b *eventBus) setClientIP(ip string) {
	b.mu.Lock()
	b.state.ClientIP = ip
	b.mu.Unlock()
}

func (b *eventBus) setConnectedAt(ts int64) {
	b.mu.Lock()
	b.state.ConnectedAt = ts
	b.mu.Unlock()
}

func (b *eventBus) resetToIdle() {
	b.mu.Lock()
	b.state = State{Phase: PhaseIdle, Since: time.Now().UnixMilli()}
	b.mu.Unlock()
	b.publish(Event{Kind: KindState, Level: LevelInfo, Message: "已断开 / disconnected"})
}

func (b *eventBus) publish(ev Event) {
	b.mu.Lock()
	b.seq++
	ev.Seq = b.seq
	if ev.TS == 0 {
		ev.TS = time.Now().UnixMilli()
	}
	b.events = append(b.events, ev)
	if len(b.events) > eventBufferSize {
		drop := len(b.events) - eventBufferSize
		b.events = append([]Event(nil), b.events[drop:]...)
	}
	subs := make([]chan Event, 0, len(b.subs))
	for _, ch := range b.subs {
		subs = append(subs, ch)
	}
	b.mu.Unlock()

	for _, ch := range subs {
		select {
		case ch <- ev:
		default:
		}
	}
}

func (b *eventBus) subscribe() (int, chan Event) {
	ch := make(chan Event, 32)
	b.mu.Lock()
	defer b.mu.Unlock()
	id := b.next
	b.next++
	b.subs[id] = ch
	return id, ch
}

func (b *eventBus) unsubscribe(id int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if ch, ok := b.subs[id]; ok {
		delete(b.subs, id)
		close(ch)
	}
}

// since returns the events with Seq > seq and the next sequence number to ask
// for. When the requested sequence is older than what is buffered the whole
// buffer is returned.
func (b *eventBus) since(seq int64) ([]Event, int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]Event, 0, len(b.events))
	for _, ev := range b.events {
		if ev.Seq > seq {
			out = append(out, ev)
		}
	}
	return out, b.seq
}

func (b *eventBus) log(level, format string, args ...any) {
	b.publish(Event{Kind: KindLog, Level: level, Message: sprintf(format, args...)})
}

// logSink adapts the standard library logger used by zju-connect into the
// event stream. Native messaging uses stdout as its framing channel, so every
// upstream log line must be captured here instead of reaching the process
// stdout.
type logSink struct {
	bus  *eventBus
	mu   sync.Mutex
	line strings.Builder
}

func (s *logSink) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, b := range p {
		if b == '\n' {
			msg := strings.TrimRight(s.line.String(), "\r")
			s.line.Reset()
			msg = strings.TrimSpace(msg)
			if msg != "" {
				s.bus.publish(Event{Kind: KindLog, Level: LevelInfo, Message: redact(msg)})
			}
			continue
		}
		s.line.WriteByte(b)
		if s.line.Len() > 8192 {
			msg := s.line.String()
			s.line.Reset()
			s.bus.publish(Event{Kind: KindLog, Level: LevelInfo, Message: redact(msg)})
		}
	}
	return len(p), nil
}
