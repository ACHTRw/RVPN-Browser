package rvpn

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync/atomic"
	"time"
)

// Protocol error codes (see docs/PROTOCOL.md).
const (
	CodeInvalidConfig   = "INVALID_CONFIG"
	CodeAlreadyRunning  = "ALREADY_RUNNING"
	CodeNotRunning      = "NOT_RUNNING"
	CodeAuthFailed      = "AUTH_FAILED"
	CodeNetworkError    = "NETWORK_ERROR"
	CodeServerRejected  = "SERVER_REJECTED"
	CodeTimeout         = "TIMEOUT"
	CodeInternal        = "INTERNAL"
	CodeNoSuchChallenge = "NO_SUCH_CHALLENGE"
)

// APIError carries a protocol error code alongside a user presentable message.
type APIError struct {
	Code string
	Err  error
}

func (e *APIError) Error() string {
	if e.Err == nil {
		return e.Code
	}
	return e.Err.Error()
}

func (e *APIError) Unwrap() error { return e.Err }

// ErrorCodeOf extracts the protocol error code of an error.
func ErrorCodeOf(err error) string {
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		return apiErr.Code
	}
	if err == nil {
		return ""
	}
	return classifyError(err)
}

// classifyError maps transport level failures onto protocol error codes.
func classifyError(err error) string {
	switch {
	case err == nil:
		return ""
	case errors.Is(err, context.DeadlineExceeded):
		return CodeTimeout
	case errors.Is(err, ErrNoSuchChallenge):
		return CodeNoSuchChallenge
	}

	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return CodeTimeout
	}

	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "no such host"),
		strings.Contains(msg, "connection refused"),
		strings.Contains(msg, "i/o timeout"),
		strings.Contains(msg, "network is unreachable"),
		strings.Contains(msg, "tls"),
		strings.Contains(msg, "eof"):
		return CodeNetworkError
	case strings.Contains(msg, "password"),
		strings.Contains(msg, "auth"),
		strings.Contains(msg, "login"),
		strings.Contains(msg, "验证码"),
		strings.Contains(msg, "用户名"):
		return CodeAuthFailed
	default:
		return CodeInternal
	}
}

// publicError removes any credential that might have leaked into an error
// message and returns a readable string.
func publicError(err error) string {
	if err == nil {
		return ""
	}
	return redact(strings.TrimSpace(err.Error()))
}

var debugEnabled atomic.Bool

// SetDebug toggles debug logging for the core.
func SetDebug(enabled bool) { debugEnabled.Store(enabled) }

// DebugEnabled reports whether debug logging is on.
func DebugEnabled() bool { return debugEnabled.Load() }

func debugLog(bus *eventBus, format string, args ...any) {
	if debugEnabled.Load() {
		bus.publish(Event{Kind: KindLog, Level: LevelDebug, Message: redact(sprintf(format, args...))})
	}
}

const reachabilityTimeout = 8 * time.Second
