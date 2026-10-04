package sshvpn

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// dnsClient 把 UDP DNS 查詢轉成 DNS-over-TCP,經 SSH 送往上游。
// 單一 TCP 連線上做 pipelining(RFC 7766):重編 query ID、依 ID 分派回應,
// 避免每個查詢都付出開 channel + 遠端 TCP 握手的往返成本。
type dnsClient struct {
	dial     func(ctx context.Context, addr string) (net.Conn, error)
	upstream string
	log      *logger
	cache    *dnsCache

	mu      sync.Mutex
	conn    *dnsConn
	connErr chan struct{}

	queries atomic.Int64
	hits    atomic.Int64
}

type dnsConn struct {
	c       net.Conn
	wmu     sync.Mutex
	mu      sync.Mutex
	pending map[uint16]chan []byte
	nextID  uint16
	closed  bool
}

const dnsTimeout = 6 * time.Second

func newDNSClient(dial func(context.Context, string) (net.Conn, error), upstream string, cache bool, log *logger) *dnsClient {
	d := &dnsClient{dial: dial, upstream: upstream, log: log}
	if cache {
		d.cache = newDNSCache(4096)
	}
	return d
}

func (d *dnsClient) close() {
	d.mu.Lock()
	c := d.conn
	d.conn = nil
	d.mu.Unlock()
	if c != nil {
		c.fail()
	}
}

func (d *dnsClient) getConn(ctx context.Context) (*dnsConn, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.conn != nil && !d.conn.isClosed() {
		return d.conn, nil
	}
	c, err := d.dial(ctx, d.upstream)
	if err != nil {
		return nil, err
	}
	dc := &dnsConn{c: c, pending: make(map[uint16]chan []byte), nextID: uint16(time.Now().UnixNano())}
	d.conn = dc
	go dc.readLoop(d.log)
	return dc, nil
}

func (c *dnsConn) isClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

func (c *dnsConn) fail() {
	c.mu.Lock()
	if !c.closed {
		c.closed = true
		for id, ch := range c.pending {
			close(ch)
			delete(c.pending, id)
		}
	}
	c.mu.Unlock()
	c.c.Close()
}

func (c *dnsConn) readLoop(log *logger) {
	defer c.fail()
	var hdr [2]byte
	for {
		// 閒置 2 分鐘無查詢即釋放 channel
		_ = c.c.SetReadDeadline(time.Now().Add(2 * time.Minute))
		if _, err := io.ReadFull(c.c, hdr[:]); err != nil {
			return
		}
		n := binary.BigEndian.Uint16(hdr[:])
		msg := make([]byte, n)
		if _, err := io.ReadFull(c.c, msg); err != nil {
			return
		}
		if n < 12 {
			continue
		}
		id := binary.BigEndian.Uint16(msg)
		c.mu.Lock()
		ch, ok := c.pending[id]
		delete(c.pending, id)
		c.mu.Unlock()
		if ok {
			ch <- msg
		}
	}
}

func (c *dnsConn) exchange(ctx context.Context, q []byte) ([]byte, error) {
	ch := make(chan []byte, 1)
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, net.ErrClosed
	}
	var id uint16
	for {
		c.nextID++
		id = c.nextID
		if _, used := c.pending[id]; !used {
			break
		}
	}
	c.pending[id] = ch
	c.mu.Unlock()

	buf := make([]byte, 2+len(q))
	binary.BigEndian.PutUint16(buf, uint16(len(q)))
	copy(buf[2:], q)
	binary.BigEndian.PutUint16(buf[2:], id)

	c.wmu.Lock()
	_ = c.c.SetWriteDeadline(time.Now().Add(dnsTimeout))
	_, err := c.c.Write(buf)
	c.wmu.Unlock()
	if err != nil {
		c.fail()
		return nil, err
	}

	select {
	case resp, ok := <-ch:
		if !ok {
			return nil, net.ErrClosed
		}
		return resp, nil
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, ctx.Err()
	}
}

// Query 回傳與 q 相同 ID 的回應。
func (d *dnsClient) Query(ctx context.Context, q []byte) ([]byte, error) {
	if len(q) < 12 {
		return nil, errors.New("short dns query")
	}
	d.queries.Add(1)
	origID := binary.BigEndian.Uint16(q)
	key := cacheKey(q)
	if d.cache != nil && key != "" {
		if r := d.cache.get(key); r != nil {
			d.hits.Add(1)
			binary.BigEndian.PutUint16(r, origID)
			return r, nil
		}
	}
	var resp []byte
	var err error
	for attempt := 0; attempt < 2; attempt++ {
		var c *dnsConn
		qctx, cancel := context.WithTimeout(ctx, dnsTimeout)
		c, err = d.getConn(qctx)
		if err == nil {
			resp, err = c.exchange(qctx, q)
		}
		cancel()
		if err == nil || ctx.Err() != nil {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	binary.BigEndian.PutUint16(resp, origID)
	if d.cache != nil && key != "" {
		if ttl, ok := cacheableTTL(resp); ok {
			d.cache.put(key, resp, ttl)
		}
	}
	return resp, nil
}

func cacheKey(q []byte) string {
	var p dnsmessage.Parser
	h, err := p.Start(q)
	if err != nil || h.Response {
		return ""
	}
	qs, err := p.AllQuestions()
	if err != nil || len(qs) != 1 {
		return ""
	}
	// 帶 EDNS 的查詢(例如 DO bit)回應格式可能不同,把 additional 段摘要進 key
	var b strings.Builder
	b.WriteString(strings.ToLower(qs[0].Name.String()))
	b.WriteByte('|')
	b.WriteString(qs[0].Type.String())
	b.WriteByte('|')
	b.WriteString(qs[0].Class.String())
	if h.RecursionDesired {
		b.WriteString("|rd")
	}
	if h.CheckingDisabled {
		b.WriteString("|cd")
	}
	if p.SkipAllAnswers() == nil && p.SkipAllAuthorities() == nil {
		if rr, err := p.AllAdditionals(); err == nil {
			for _, r := range rr {
				if r.Header.Type == dnsmessage.TypeOPT {
					b.WriteString("|opt")
					if r.Header.TTL&(1<<15) != 0 {
						b.WriteString("+do")
					}
				}
			}
		}
	}
	return b.String()
}

func cacheableTTL(resp []byte) (time.Duration, bool) {
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || h.Truncated {
		return 0, false
	}
	if h.RCode != dnsmessage.RCodeSuccess && h.RCode != dnsmessage.RCodeNameError {
		return 0, false
	}
	if err := p.SkipAllQuestions(); err != nil {
		return 0, false
	}
	minTTL := uint32(0)
	found := false
	for {
		rh, err := p.AnswerHeader()
		if err != nil {
			break
		}
		if !found || rh.TTL < minTTL {
			minTTL = rh.TTL
		}
		found = true
		_ = p.SkipAnswer()
	}
	if !found {
		minTTL = 30 // 負面回應
	}
	ttl := time.Duration(minTTL) * time.Second
	if ttl < 5*time.Second {
		return 0, false
	}
	return min(ttl, time.Hour), true
}

type dnsCacheEntry struct {
	resp    []byte
	expires time.Time
}

type dnsCache struct {
	mu  sync.Mutex
	cap int
	m   map[string]dnsCacheEntry
}

func newDNSCache(capacity int) *dnsCache {
	return &dnsCache{cap: capacity, m: make(map[string]dnsCacheEntry)}
}

func (c *dnsCache) get(k string) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok {
		return nil
	}
	if time.Now().After(e.expires) {
		delete(c.m, k)
		return nil
	}
	return adjustTTL(e.resp, e.expires)
}

func (c *dnsCache) put(k string, resp []byte, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.m) >= c.cap {
		now := time.Now()
		for key, e := range c.m {
			if now.After(e.expires) {
				delete(c.m, key)
			}
		}
		// 還是滿的話隨機淘汰(map 迭代順序隨機)
		for key := range c.m {
			if len(c.m) < c.cap {
				break
			}
			delete(c.m, key)
		}
	}
	cp := make([]byte, len(resp))
	copy(cp, resp)
	c.m[k] = dnsCacheEntry{resp: cp, expires: time.Now().Add(ttl)}
}

// adjustTTL 複製快取回應並把所有 RR 的 TTL 改成剩餘秒數。
func adjustTTL(resp []byte, expires time.Time) []byte {
	remain := uint32(time.Until(expires).Seconds())
	if remain == 0 {
		remain = 1
	}
	var msg dnsmessage.Message
	if err := msg.Unpack(resp); err != nil {
		cp := make([]byte, len(resp))
		copy(cp, resp)
		return cp
	}
	set := func(rs []dnsmessage.Resource) {
		for i := range rs {
			if rs[i].Header.Type != dnsmessage.TypeOPT {
				rs[i].Header.TTL = min(rs[i].Header.TTL, remain)
			}
		}
	}
	set(msg.Answers)
	set(msg.Authorities)
	set(msg.Additionals)
	out, err := msg.Pack()
	if err != nil {
		cp := make([]byte, len(resp))
		copy(cp, resp)
		return cp
	}
	return out
}

func isAAAAQuery(q []byte) bool {
	var p dnsmessage.Parser
	if _, err := p.Start(q); err != nil {
		return false
	}
	qs, err := p.AllQuestions()
	return err == nil && len(qs) == 1 && qs[0].Type == dnsmessage.TypeAAAA
}

// emptyAnswer 產生「名稱存在但沒有此類型記錄」的回應(NOERROR、無 answer),
// 與真正沒有 AAAA 的網域相同,App 會直接改用 A 記錄。
func emptyAnswer(q []byte) []byte {
	var p dnsmessage.Parser
	h, err := p.Start(q)
	if err != nil {
		return nil
	}
	qs, err := p.AllQuestions()
	if err != nil {
		return nil
	}
	h.Response = true
	h.RecursionAvailable = true
	h.RCode = dnsmessage.RCodeSuccess
	out, err := (&dnsmessage.Message{Header: h, Questions: qs}).Pack()
	if err != nil {
		return nil
	}
	return out
}
