package rvpn

import (
	"context"
	"net"
	"sync"

	"github.com/mythologyli/zju-connect/client"
	"github.com/mythologyli/zju-connect/underlay"
)

// trackingDialer wraps the underlay dialer and remembers every connection it
// hands out, so that a session can release all of them when it stops.
//
// Why this is needed: the upstream protocol clients build their own HTTP
// transports on top of the underlay dialer and do not close them in Close().
// A transport without an idle timeout keeps its keep-alive socket to the VPN
// server open indefinitely, which means every start/stop cycle would leak one
// file descriptor. Owning the connections here keeps the engine leak free
// without forking the protocol library.
type trackingDialer struct {
	inner *underlay.Dialer

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

var _ client.UnderlayDialer = (*trackingDialer)(nil)

func newTrackingDialer(inner *underlay.Dialer) *trackingDialer {
	return &trackingDialer{inner: inner, conns: make(map[net.Conn]struct{})}
}

func (d *trackingDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	conn, err := d.inner.DialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}

	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		_ = conn.Close()
		return nil, net.ErrClosed
	}
	d.conns[conn] = struct{}{}
	d.mu.Unlock()
	return &trackedConn{Conn: conn, owner: d}, nil
}

// ExcludeIP is part of the upstream dialer contract (TUN mode uses it).
func (d *trackingDialer) ExcludeIP(ip net.IP) {
	d.inner.ExcludeIP(ip)
}

// CloseAll closes every connection created through this dialer and rejects new
// ones. It is safe to call more than once.
func (d *trackingDialer) CloseAll() {
	d.mu.Lock()
	conns := d.conns
	d.conns = make(map[net.Conn]struct{})
	d.closed = true
	d.mu.Unlock()

	for conn := range conns {
		_ = conn.Close()
	}
}

// OpenConnections reports how many connections are currently tracked; used by
// the tests to prove that nothing leaks.
func (d *trackingDialer) OpenConnections() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.conns)
}

func (d *trackingDialer) forget(conn net.Conn) {
	d.mu.Lock()
	delete(d.conns, conn)
	d.mu.Unlock()
}

// trackedConn removes itself from the tracker when it is closed normally.
type trackedConn struct {
	net.Conn
	owner *trackingDialer
	once  sync.Once
}

func (c *trackedConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { c.owner.forget(c.Conn) })
	return err
}
