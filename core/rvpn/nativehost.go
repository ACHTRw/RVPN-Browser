package rvpn

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// maxHostMessage is the largest reply Chrome accepts from a native messaging
// host (1 MB). Replies are trimmed to stay well below it.
const maxHostMessage = 900 * 1024

// maxEventsPerPoll bounds the size of a single status reply.
const maxEventsPerPoll = 200

// readNativeMessage reads one Chrome native messaging frame.
func readNativeMessage(r *bufio.Reader) ([]byte, error) {
	var length uint32
	if err := binary.Read(r, binary.LittleEndian, &length); err != nil {
		return nil, err
	}
	if length == 0 {
		return nil, nil
	}
	if length > 64*1024*1024 {
		return nil, fmt.Errorf("native message too large: %d bytes", length)
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// writeNativeMessage writes one Chrome native messaging frame.
func writeNativeMessage(w io.Writer, payload []byte) error {
	if err := binary.Write(w, binary.LittleEndian, uint32(len(payload))); err != nil {
		return err
	}
	if _, err := w.Write(payload); err != nil {
		return err
	}
	if f, ok := w.(interface{ Flush() error }); ok {
		return f.Flush()
	}
	return nil
}

// ServeStdio runs the Chrome native messaging loop until stdin is closed or a
// shutdown command is received.
func ServeStdio(h *Handler, in io.Reader, out io.Writer) error {
	reader := bufio.NewReaderSize(in, 64*1024)
	writer := bufio.NewWriterSize(out, 64*1024)

	for {
		payload, err := readNativeMessage(reader)
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}
		if payload == nil {
			continue
		}
		resp := h.HandleRaw(payload)
		if len(resp) > maxHostMessage {
			resp = mustMarshal(Response{
				OK:    false,
				Error: "response too large; reduce the requested event batch",
				Code:  CodeInternal,
			})
		}
		if err := writeNativeMessage(writer, resp); err != nil {
			return err
		}

		select {
		case <-h.ShutdownRequested():
			return nil
		default:
		}
	}
}

// ServeTCP exposes the same protocol on a loopback TCP port. It is used by
// front-ends that cannot use native messaging (for example the Android
// fallback UI or local debugging) and is protected by a shared token.
type tcpServer struct {
	handler *Handler
	token   string
	ln      net.Listener

	mu      sync.Mutex
	closing bool
	wg      sync.WaitGroup
}

// ServeTCP listens on addr (must be a loopback address) and serves the JSON
// protocol, one request per line, until the listener is closed.
func ServeTCP(h *Handler, addr, token string) (*tcpServer, error) {
	if err := checkLoopbackBind("tcp", addr); err != nil {
		return nil, err
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	if token == "" {
		token = randomToken()
	}
	srv := &tcpServer{handler: h, token: token, ln: ln}
	srv.wg.Add(1)
	go srv.acceptLoop()
	return srv, nil
}

// Addr returns the address the server is listening on.
func (s *tcpServer) Addr() string { return s.ln.Addr().String() }

// Token returns the shared secret required by clients.
func (s *tcpServer) Token() string { return s.token }

// Close stops the server.
func (s *tcpServer) Close() error {
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		return nil
	}
	s.closing = true
	s.mu.Unlock()
	err := s.ln.Close()
	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
	}
	return err
}

func (s *tcpServer) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.serveConn(conn)
		}()
	}
}

func (s *tcpServer) serveConn(conn net.Conn) {
	defer func() { _ = conn.Close() }()
	reader := bufio.NewReaderSize(conn, 64*1024)
	writer := bufio.NewWriter(conn)

	authed := s.token == ""
	for {
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Minute))
		line, err := reader.ReadBytes('\n')
		if err != nil {
			return
		}
		line = []byte(strings.TrimSpace(string(line)))
		if len(line) == 0 {
			continue
		}

		if !authed {
			var probe struct {
				Token string `json:"token"`
			}
			if err := json.Unmarshal(line, &probe); err != nil || probe.Token != s.token {
				_, _ = writer.WriteString(`{"ok":false,"error":"unauthorised","code":"INTERNAL"}` + "\n")
				_ = writer.Flush()
				return
			}
			authed = true
			_, _ = writer.WriteString(`{"ok":true,"data":{"authenticated":true}}` + "\n")
			_ = writer.Flush()
			continue
		}

		resp := s.handler.HandleRaw(line)
		if len(resp) > maxHostMessage {
			resp = mustMarshal(Response{OK: false, Error: "response too large", Code: CodeInternal})
		}
		if _, err := writer.Write(resp); err != nil {
			return
		}
		if err := writer.WriteByte('\n'); err != nil {
			return
		}
		if err := writer.Flush(); err != nil {
			return
		}
	}
}
