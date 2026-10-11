package rvpn

import (
	"encoding/base64"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/mythologyli/zju-connect/client/authchallenge"
)

// challengeTimeout bounds how long a login waits for the user to answer an
// interactive authentication step. It matches the upstream CLI default.
const challengeTimeout = 5 * time.Minute

// ErrNoSuchChallenge is returned when the front-end answers a challenge that
// is not pending (already answered, cancelled or expired).
var ErrNoSuchChallenge = errors.New("no such challenge")

type challengeAnswer struct {
	code              string
	points            []authchallenge.Point
	width             int
	height            int
	skipSecondaryAuth bool
	callbackURL       string
}

type pendingChallenge struct {
	challenge Challenge
	ch        chan challengeAnswer
	// done is closed when the session shuts down, so that a pending challenge
	// returns immediately instead of blocking the protocol goroutine until the
	// timeout expires.
	done chan struct{}
}

type challengeManager struct {
	bus *eventBus

	mu      sync.Mutex
	pending map[string]*pendingChallenge
	closed  bool
}

func newChallengeManager(bus *eventBus) *challengeManager {
	return &challengeManager{bus: bus, pending: make(map[string]*pendingChallenge)}
}

var _ authchallenge.Handler = (*challengeManager)(nil)

func (m *challengeManager) ask(c Challenge, timeout time.Duration) (challengeAnswer, error) {
	if timeout <= 0 {
		timeout = challengeTimeout
	}
	c.CreatedAt = time.Now().UnixMilli()
	c.Deadline = time.Now().Add(timeout).UnixMilli()

	pc := &pendingChallenge{challenge: c, ch: make(chan challengeAnswer, 1), done: make(chan struct{})}

	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return challengeAnswer{}, errors.New("session is shutting down")
	}
	m.pending[c.ID] = pc
	m.mu.Unlock()

	m.bus.publish(Event{
		Kind:      KindChallenge,
		Level:     LevelInfo,
		Message:   c.Message,
		Challenge: &c,
	})

	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case ans := <-pc.ch:
		m.finish(c.ID, "answered")
		return ans, nil
	case <-pc.done:
		m.finish(c.ID, "cancelled")
		return challengeAnswer{}, errors.New("会话已断开 / session stopped while waiting for input")
	case <-timer.C:
		m.finish(c.ID, "timeout")
		return challengeAnswer{}, errors.New("验证超时，请重试 / authentication timed out, please retry")
	}
}

func (m *challengeManager) finish(id, reason string) {
	m.mu.Lock()
	_, ok := m.pending[id]
	delete(m.pending, id)
	m.mu.Unlock()
	if ok {
		m.bus.publish(Event{Kind: KindChallengeDone, Level: LevelInfo, Message: reason})
	}
}

// Answer delivers a user response to a pending challenge.
func (m *challengeManager) Answer(id string, ans challengeAnswer) error {
	m.mu.Lock()
	pc, ok := m.pending[id]
	m.mu.Unlock()
	if !ok {
		return ErrNoSuchChallenge
	}
	select {
	case pc.ch <- ans:
		return nil
	default:
		return fmt.Errorf("challenge %s already answered", id)
	}
}

// Pending returns the challenges that are currently waiting for input. The
// front-end uses this to restore its UI after a restart.
func (m *challengeManager) Pending() []Challenge {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Challenge, 0, len(m.pending))
	for _, pc := range m.pending {
		out = append(out, pc.challenge)
	}
	return out
}

// Close cancels every pending challenge; it is safe to call more than once.
func (m *challengeManager) Close() {
	m.mu.Lock()
	pending := m.pending
	m.pending = make(map[string]*pendingChallenge)
	m.closed = true
	m.mu.Unlock()
	for id, pc := range pending {
		close(pc.done)
		m.bus.publish(Event{Kind: KindChallengeDone, Level: LevelWarn, Message: "cancelled: " + id})
	}
}

// reset re-arms the manager for a new session.
func (m *challengeManager) reset() {
	m.mu.Lock()
	for id, pc := range m.pending {
		close(pc.done)
		m.bus.publish(Event{Kind: KindChallengeDone, Level: LevelWarn, Message: "cancelled: " + id})
	}
	m.pending = make(map[string]*pendingChallenge)
	m.closed = false
	m.mu.Unlock()
}

func (m *challengeManager) HandleCodeChallenge(ch authchallenge.CodeChallenge) (authchallenge.CodeResponse, error) {
	kind := ChallengeText
	switch ch.Kind {
	case authchallenge.CodeSMS:
		kind = ChallengeSMSCode
	case authchallenge.CodeTOTP:
		kind = ChallengeTOTP
	case authchallenge.CodeRadius:
		kind = ChallengeRadiusCode
	}
	if ch.CanSkipSecondaryAuth {
		kind = ChallengeSecondaryAuth
	}
	ans, err := m.ask(Challenge{
		ID:                   nextChallengeID(),
		Type:                 kind,
		Message:              ch.Message,
		CanSkipSecondaryAuth: ch.CanSkipSecondaryAuth,
	}, challengeTimeout)
	if err != nil {
		return authchallenge.CodeResponse{}, err
	}
	return authchallenge.CodeResponse{Code: ans.code, SkipSecondaryAuth: ans.skipSecondaryAuth}, nil
}

func (m *challengeManager) HandleTextCaptcha(ch authchallenge.TextCaptchaChallenge) (authchallenge.TextCaptchaResponse, error) {
	ans, err := m.ask(Challenge{
		ID:           nextChallengeID(),
		Type:         ChallengeText,
		Message:      ch.Message,
		ImageDataURL: imageDataURL(ch.Image),
	}, challengeTimeout)
	if err != nil {
		return authchallenge.TextCaptchaResponse{}, err
	}
	return authchallenge.TextCaptchaResponse{Code: ans.code}, nil
}

func (m *challengeManager) HandleClickCaptcha(ch authchallenge.ClickCaptchaChallenge) (authchallenge.ClickCaptchaResponse, error) {
	ans, err := m.ask(Challenge{
		ID:           nextChallengeID(),
		Type:         ChallengeClickCode,
		Message:      ch.Message,
		ImageDataURL: imageDataURL(ch.Image),
	}, challengeTimeout)
	if err != nil {
		return authchallenge.ClickCaptchaResponse{}, err
	}
	return authchallenge.ClickCaptchaResponse{Points: ans.points, Width: ans.width, Height: ans.height}, nil
}

func (m *challengeManager) HandleExternalLogin(ch authchallenge.ExternalLoginChallenge) (authchallenge.ExternalLoginResponse, error) {
	msg := ch.Message
	if msg == "" {
		msg = "请在浏览器中完成登录，并把回调地址粘贴回来 / Complete the login in your browser and paste the callback URL"
	}
	ans, err := m.ask(Challenge{
		ID:       nextChallengeID(),
		Type:     ChallengeExternalLogin,
		Message:  msg,
		LoginURL: ch.LoginURL,
	}, challengeTimeout)
	if err != nil {
		return authchallenge.ExternalLoginResponse{}, err
	}
	if ans.callbackURL != "" {
		return authchallenge.ExternalLoginResponse{CallbackURL: ans.callbackURL}, nil
	}
	return authchallenge.ExternalLoginResponse{CallbackURL: ans.code}, nil
}

// imageDataURL converts raw captcha bytes into a data URL, detecting the
// actual image format instead of assuming one.
func imageDataURL(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	mime := "image/png"
	switch {
	case len(data) > 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF:
		mime = "image/jpeg"
	case len(data) > 6 && string(data[0:6]) == "GIF87a", len(data) > 6 && string(data[0:6]) == "GIF89a":
		mime = "image/gif"
	case len(data) > 12 && string(data[0:4]) == "RIFF" && string(data[8:12]) == "WEBP":
		mime = "image/webp"
	case len(data) > 8 && string(data[0:8]) == "\x89PNG\r\n\x1a\n":
		mime = "image/png"
	case len(data) > 5 && string(data[0:5]) == "<?xml", len(data) > 4 && string(data[0:4]) == "<svg":
		mime = "image/svg+xml"
	}
	return "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(data)
}
