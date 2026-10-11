package rvpn

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mythologyli/zju-connect/client/authchallenge"
)

func TestEventBusIncremental(t *testing.T) {
	bus := newEventBus()
	for i := 0; i < 5; i++ {
		bus.log(LevelInfo, "line %d", i)
	}

	events, next := bus.since(0)
	if len(events) != 5 || next != 5 {
		t.Fatalf("first poll: %d events, next=%d", len(events), next)
	}
	if events[0].Seq != 1 || events[4].Seq != 5 {
		t.Fatalf("unexpected sequence numbers: %d..%d", events[0].Seq, events[4].Seq)
	}

	events, next = bus.since(3)
	if len(events) != 2 || events[0].Seq != 4 || next != 5 {
		t.Fatalf("incremental poll wrong: %d events, next=%d", len(events), next)
	}

	// Never lose the tail when the caller lags behind the ring buffer.
	for i := 0; i < eventBufferSize+50; i++ {
		bus.log(LevelInfo, "spam %d", i)
	}
	events, _ = bus.since(0)
	if len(events) != eventBufferSize {
		t.Fatalf("ring buffer not bounded: %d", len(events))
	}
}

func TestEventBusStateTransitions(t *testing.T) {
	bus := newEventBus()
	cfg := &Config{Protocol: ProtocolATrust, UserType: UserTypeZJU, Server: "vpn.zju.edu.cn", Port: 443}
	bus.resetState(cfg)
	if got := bus.State(); got.Phase != PhaseIdle || got.Server != "vpn.zju.edu.cn:443" {
		t.Fatalf("unexpected initial state: %+v", got)
	}

	bus.setPhase(PhaseConnecting, "connecting")
	first := bus.State().Since
	time.Sleep(2 * time.Millisecond)
	bus.setPhase(PhaseConnecting, "still connecting")
	if bus.State().Since != first {
		t.Error("phase timestamp must not change while the phase stays the same")
	}

	bus.setAddrs("127.0.0.1:1", "127.0.0.1:2")
	bus.setClientIP("10.0.0.1")
	bus.setConnectedAt(123)
	bus.setPhase(PhaseConnected, "ok")
	state := bus.State()
	if state.SocksAddr != "127.0.0.1:1" || state.ClientIP != "10.0.0.1" || state.ConnectedAt != 123 {
		t.Fatalf("state not updated: %+v", state)
	}

	bus.setError("boom")
	state = bus.State()
	if state.Phase != PhaseError || state.LastError != "boom" || state.SocksAddr != "" {
		t.Fatalf("error state wrong: %+v", state)
	}

	bus.resetToIdle()
	if bus.State().Phase != PhaseIdle {
		t.Fatalf("reset failed: %+v", bus.State())
	}
}

func TestChallengeRelay(t *testing.T) {
	bus := newEventBus()
	cm := newChallengeManager(bus)
	defer cm.Close()

	subID, ch := bus.subscribe()
	defer bus.unsubscribe(subID)

	done := make(chan authchallenge.TextCaptchaResponse, 1)
	go func() {
		resp, err := cm.HandleTextCaptcha(authchallenge.TextCaptchaChallenge{
			Image:   []byte("\x89PNG\r\n\x1a\nfake"),
			Message: "enter the code",
		})
		if err != nil {
			t.Errorf("handler error: %v", err)
			done <- authchallenge.TextCaptchaResponse{}
			return
		}
		done <- resp
	}()

	ev := waitForChallenge(t, ch)
	if ev.Challenge == nil || ev.Challenge.Type != ChallengeText {
		t.Fatalf("unexpected challenge event: %+v", ev)
	}
	if !strings.HasPrefix(ev.Challenge.ImageDataURL, "data:image/png;base64,") {
		t.Fatalf("captcha image not relayed: %q", ev.Challenge.ImageDataURL)
	}
	if len(cm.Pending()) != 1 {
		t.Fatalf("challenge not pending: %d", len(cm.Pending()))
	}

	if err := cm.Answer(ev.Challenge.ID, challengeAnswer{code: "1234"}); err != nil {
		t.Fatalf("answer failed: %v", err)
	}
	select {
	case resp := <-done:
		if resp.Code != "1234" {
			t.Fatalf("wrong code relayed: %+v", resp)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not return after the answer")
	}

	if err := cm.Answer(ev.Challenge.ID, challengeAnswer{code: "1234"}); err != ErrNoSuchChallenge {
		t.Fatalf("answering a finished challenge must fail with ErrNoSuchChallenge, got %v", err)
	}
}

func TestChallengeKinds(t *testing.T) {
	bus := newEventBus()
	cm := newChallengeManager(bus)
	defer cm.Close()
	subID, ch := bus.subscribe()
	defer bus.unsubscribe(subID)

	cases := []struct {
		name string
		run  func()
		want string
	}{
		{"sms", func() { _, _ = cm.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeSMS}) }, ChallengeSMSCode},
		{"totp", func() { _, _ = cm.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeTOTP}) }, ChallengeTOTP},
		{"radius", func() { _, _ = cm.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeRadius}) }, ChallengeRadiusCode},
		{"secondary", func() {
			_, _ = cm.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeSMS, CanSkipSecondaryAuth: true})
		}, ChallengeSecondaryAuth},
		{"click", func() { _, _ = cm.HandleClickCaptcha(authchallenge.ClickCaptchaChallenge{Image: []byte("x")}) }, ChallengeClickCode},
		{"external", func() {
			_, _ = cm.HandleExternalLogin(authchallenge.ExternalLoginChallenge{LoginURL: "https://cas.zju.edu.cn"})
		}, ChallengeExternalLogin},
	}

	for _, tc := range cases {
		go tc.run()
		ev := waitForChallenge(t, ch)
		if ev.Challenge == nil || ev.Challenge.Type != tc.want {
			t.Fatalf("%s: got %+v, want %s", tc.name, ev.Challenge, tc.want)
		}
		_ = cm.Answer(ev.Challenge.ID, challengeAnswer{code: "x", callbackURL: "https://x"})
	}
}

func TestChallengeCloseCancels(t *testing.T) {
	bus := newEventBus()
	cm := newChallengeManager(bus)

	errCh := make(chan error, 1)
	go func() {
		_, err := cm.HandleTextCaptcha(authchallenge.TextCaptchaChallenge{Image: []byte("x")})
		errCh <- err
	}()
	time.Sleep(50 * time.Millisecond)
	cm.Close()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("closing the manager must fail pending challenges")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("pending challenge was not cancelled")
	}
}

func TestImageDataURLFormats(t *testing.T) {
	cases := map[string]string{
		"\xff\xd8\xff\xe0jpegdata":     "data:image/jpeg;base64,",
		"\x89PNG\r\n\x1a\npngdata":     "data:image/png;base64,",
		"GIF89agifdata":                "data:image/gif;base64,",
		"RIFFxxxxWEBPVP8 ":             "data:image/webp;base64,",
		"<?xml version=\"1.0\"?><svg>": "data:image/svg+xml;base64,",
		"totally-unknown":              "data:image/png;base64,",
	}
	for input, want := range cases {
		if got := imageDataURL([]byte(input)); !strings.HasPrefix(got, want) {
			t.Errorf("imageDataURL(%q) = %q, want prefix %q", input, got, want)
		}
	}
	if imageDataURL(nil) != "" {
		t.Error("empty captcha payload must produce an empty data URL")
	}
}

func TestHandlerDispatch(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()

	// hello
	resp := call(t, h, map[string]any{"id": 1, "cmd": "hello"})
	if resp["ok"] != true {
		t.Fatalf("hello failed: %v", resp)
	}
	data := resp["data"].(map[string]any)
	if data["platform"] == "" || len(data["capabilities"].([]any)) == 0 {
		t.Fatalf("incomplete hello reply: %v", data)
	}

	// unknown command
	resp = call(t, h, map[string]any{"id": 2, "cmd": "explode"})
	if resp["ok"] != false || !strings.Contains(resp["error"].(string), "unknown command") {
		t.Fatalf("unknown command not rejected: %v", resp)
	}

	// malformed payload must not crash the host
	out := h.HandleRaw([]byte("{not json"))
	var parsed map[string]any
	if err := json.Unmarshal(out, &parsed); err != nil {
		t.Fatalf("reply is not JSON: %s", out)
	}
	if parsed["ok"] != false {
		t.Fatalf("malformed request must fail: %v", parsed)
	}

	// status with no session
	resp = call(t, h, map[string]any{"id": 3, "cmd": "status", "since": 0})
	data = resp["data"].(map[string]any)
	state := data["state"].(map[string]any)
	if state["phase"] != PhaseIdle {
		t.Fatalf("unexpected idle state: %v", state)
	}

	// invalid config
	resp = call(t, h, map[string]any{"id": 4, "cmd": "start", "config": map[string]any{"userType": "other"}})
	if resp["ok"] != false || resp["code"] != CodeInvalidConfig {
		t.Fatalf("invalid config accepted: %v", resp)
	}

	// stop is idempotent
	for i := 0; i < 3; i++ {
		resp = call(t, h, map[string]any{"id": 5 + i, "cmd": "stop"})
		if resp["ok"] != true {
			t.Fatalf("stop #%d failed: %v", i, resp)
		}
	}

	// answering an unknown challenge
	resp = call(t, h, map[string]any{"id": 9, "cmd": "answer", "challengeId": "nope", "code": "1"})
	if resp["ok"] != false || resp["code"] != CodeNoSuchChallenge {
		t.Fatalf("unknown challenge accepted: %v", resp)
	}
}

func TestHandlerPanicSafety(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()
	// A nil config used to be a panic risk; it must be a clean error reply.
	out := h.HandleRaw([]byte(`{"id":1,"cmd":"start"}`))
	if !strings.Contains(string(out), "INVALID_CONFIG") {
		t.Fatalf("start without config must fail cleanly: %s", out)
	}
}

func TestNativeMessagingFraming(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()

	var buf bytes.Buffer
	payload, _ := json.Marshal(map[string]any{"id": 7, "cmd": "hello"})
	if err := binary.Write(&buf, binary.LittleEndian, uint32(len(payload))); err != nil {
		t.Fatal(err)
	}
	buf.Write(payload)

	var out bytes.Buffer
	if err := ServeStdio(h, &buf, &out); err != nil {
		t.Fatalf("ServeStdio: %v", err)
	}

	length := binary.LittleEndian.Uint32(out.Bytes()[:4])
	body := out.Bytes()[4:]
	if int(length) != len(body) {
		t.Fatalf("frame length %d does not match body %d", length, len(body))
	}
	var resp map[string]any
	if err := json.Unmarshal(body, &resp); err != nil {
		t.Fatal(err)
	}
	if resp["ok"] != true {
		t.Fatalf("unexpected reply: %v", resp)
	}
}

func TestTCPControlServerRequiresToken(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()

	srv, err := ServeTCP(h, "127.0.0.1:0", "secret-token")
	if err != nil {
		t.Fatalf("ServeTCP: %v", err)
	}
	defer func() { _ = srv.Close() }()

	conn, err := netDial(srv.Addr())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = conn.Close() }()

	// Wrong token closes the connection.
	_, _ = conn.Write([]byte(`{"token":"wrong"}` + "\n"))
	buf := make([]byte, 256)
	n, _ := conn.Read(buf)
	if !strings.Contains(string(buf[:n]), "unauthorised") {
		t.Fatalf("wrong token was accepted: %s", string(buf[:n]))
	}
	_ = conn.Close()

	// Correct token gets a session.
	conn2, err := netDial(srv.Addr())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = conn2.Close() }()
	_, _ = conn2.Write([]byte(`{"token":"secret-token"}` + "\n"))
	n, _ = conn2.Read(buf)
	if !strings.Contains(string(buf[:n]), "authenticated") {
		t.Fatalf("valid token rejected: %s", string(buf[:n]))
	}
	_, _ = conn2.Write([]byte(`{"id":1,"cmd":"hello"}` + "\n"))
	n, _ = conn2.Read(buf)
	if !strings.Contains(string(buf[:n]), `"ok":true`) {
		t.Fatalf("hello over TCP failed: %s", string(buf[:n]))
	}
}

func TestTCPControlServerRejectsPublicBind(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()
	if _, err := ServeTCP(h, "0.0.0.0:0", ""); err == nil {
		t.Fatal("public bind must be rejected")
	}
}

func TestSessionStartStopValidation(t *testing.T) {
	s := NewSession()
	defer s.Close()

	if err := s.Start(Config{UserType: UserTypeOther}); err == nil {
		t.Fatal("invalid config must be rejected synchronously")
	}
	if s.Running() {
		t.Fatal("session must not be running after a rejected start")
	}
	// Stop on an idle session is a no-op.
	s.Stop()
	s.Stop()
	if s.Running() {
		t.Fatal("session must stay idle")
	}
}

func TestSessionDebugFlag(t *testing.T) {
	s := NewSession()
	defer s.Close()
	SetDebug(true)
	if !DebugEnabled() {
		t.Fatal("SetDebug(true) had no effect")
	}
	SetDebug(false)
	if DebugEnabled() {
		t.Fatal("SetDebug(false) had no effect")
	}
	_ = s
}

func TestStatusBoundsEventBatch(t *testing.T) {
	s := NewSession()
	defer s.Close()
	for i := 0; i < maxEventsPerPoll+50; i++ {
		s.bus.log(LevelInfo, "line %d", i)
	}
	status := s.Status(0)
	if len(status.Events) != maxEventsPerPoll {
		t.Fatalf("status returned %d events, want %d", len(status.Events), maxEventsPerPoll)
	}
	if status.NextSince != int64(maxEventsPerPoll+50) {
		t.Fatalf("nextSince = %d, want %d", status.NextSince, maxEventsPerPoll+50)
	}
	status2 := s.Status(status.NextSince)
	if len(status2.Events) != 0 {
		t.Fatalf("expected no new events, got %d", len(status2.Events))
	}
}

func TestHandlerConcurrentRequests(t *testing.T) {
	h := NewHandler()
	defer h.Session().Close()

	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			resp := call(t, h, map[string]any{"id": i, "cmd": "status", "since": 0})
			if resp["ok"] != true {
				t.Errorf("concurrent status failed: %v", resp)
			}
		}(i)
	}
	wg.Wait()
}

// ---------------------------------------------------------------- helpers

func call(t *testing.T, h *Handler, req map[string]any) map[string]any {
	t.Helper()
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	out := h.HandleRaw(raw)
	var resp map[string]any
	if err := json.Unmarshal(out, &resp); err != nil {
		t.Fatalf("reply is not JSON: %s", out)
	}
	return resp
}

func waitForChallenge(t *testing.T, ch chan Event) Event {
	t.Helper()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case ev := <-ch:
			if ev.Kind == KindChallenge {
				return ev
			}
		case <-deadline:
			t.Fatal("no challenge event received")
			return Event{}
		}
	}
}

func netDial(addr string) (net.Conn, error) {
	return net.DialTimeout("tcp", addr, 3*time.Second)
}
