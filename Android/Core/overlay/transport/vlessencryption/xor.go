// Adapted from MetaCubeX/mihomo (GPL-3.0-or-later).
package vlessencryption

import (
	"crypto/aes"
	"crypto/cipher"
	"net"

	"github.com/zeebo/blake3"
)

func newCTR(key, iv []byte) cipher.Stream {
	derivedKey := make([]byte, 32)
	blake3.DeriveKey("VLESS", key, derivedKey)
	block, _ := aes.NewCipher(derivedKey)
	return cipher.NewCTR(block, iv)
}

type xorConn struct {
	net.Conn
	ctr       cipher.Stream
	peerCTR   cipher.Stream
	outSkip   int
	outHeader []byte
	inSkip    int
	inHeader  []byte
}

func newXORConn(conn net.Conn, ctr, peerCTR cipher.Stream, outSkip, inSkip int) *xorConn {
	return &xorConn{
		Conn:      conn,
		ctr:       ctr,
		peerCTR:   peerCTR,
		outSkip:   outSkip,
		outHeader: make([]byte, 0, 5),
		inSkip:    inSkip,
		inHeader:  make([]byte, 0, 5),
	}
}

func (c *xorConn) Write(payload []byte) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	for remaining := payload; ; {
		if len(remaining) <= c.outSkip {
			c.outSkip -= len(remaining)
			break
		}
		remaining = remaining[c.outSkip:]
		c.outSkip = 0
		needed := 5 - len(c.outHeader)
		if len(remaining) < needed {
			c.outHeader = append(c.outHeader, remaining...)
			c.ctr.XORKeyStream(remaining, remaining)
			break
		}
		c.outSkip, _ = decodeHeader(append(c.outHeader, remaining[:needed]...))
		c.outHeader = c.outHeader[:0]
		c.ctr.XORKeyStream(remaining[:needed], remaining[:needed])
		remaining = remaining[needed:]
	}
	if _, err := c.Conn.Write(payload); err != nil {
		return 0, err
	}
	return len(payload), nil
}

func (c *xorConn) Read(payload []byte) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	length, err := c.Conn.Read(payload)
	for remaining := payload[:length]; ; {
		if len(remaining) <= c.inSkip {
			c.inSkip -= len(remaining)
			break
		}
		remaining = remaining[c.inSkip:]
		c.inSkip = 0
		needed := 5 - len(c.inHeader)
		if len(remaining) < needed {
			c.peerCTR.XORKeyStream(remaining, remaining)
			c.inHeader = append(c.inHeader, remaining...)
			break
		}
		c.peerCTR.XORKeyStream(remaining[:needed], remaining[:needed])
		c.inSkip, _ = decodeHeader(append(c.inHeader, remaining[:needed]...))
		c.inHeader = c.inHeader[:0]
		remaining = remaining[needed:]
	}
	return length, err
}
