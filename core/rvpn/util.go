package rvpn

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

func sprintf(format string, args ...any) string {
	if len(args) == 0 {
		return format
	}
	return fmt.Sprintf(format, args...)
}

var (
	secretMu sync.RWMutex
	secrets  []string
)

// registerSecrets makes sure credentials never end up in the event log even if
// an upstream component decides to print them.
func registerSecrets(values ...string) {
	secretMu.Lock()
	defer secretMu.Unlock()
	for _, v := range values {
		if len(v) < 3 {
			continue
		}
		dup := false
		for _, existing := range secrets {
			if existing == v {
				dup = true
				break
			}
		}
		if !dup {
			secrets = append(secrets, v)
		}
	}
}

func clearSecrets() {
	secretMu.Lock()
	defer secretMu.Unlock()
	secrets = nil
}

func redact(msg string) string {
	secretMu.RLock()
	defer secretMu.RUnlock()
	for _, s := range secrets {
		if s == "" {
			continue
		}
		msg = strings.ReplaceAll(msg, s, "***")
	}
	return msg
}

var challengeCounter atomic.Int64

func nextChallengeID() string {
	return fmt.Sprintf("c%d", challengeCounter.Add(1))
}

// randomToken returns a URL safe random token used by the loopback TCP
// control channel.
func randomToken() string {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("fallback-%d", time.Now().UnixNano())
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}
