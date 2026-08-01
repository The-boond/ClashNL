// Adapted from MetaCubeX/mihomo (GPL-3.0-or-later).
package vlessencryption

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"fmt"
	"io"
	mathrand "math/rand/v2"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/zeebo/blake3"
	"golang.org/x/crypto/chacha20poly1305"
)

type Conn struct {
	net.Conn
	useAES      bool
	client      *Client
	unitedKey   []byte
	preWrite    []byte
	aead        *aeadState
	peerAEAD    *aeadState
	peerPadding []byte
	rawInput    bytes.Buffer
	input       bytes.Reader
}

func newConn(conn net.Conn, useAES bool) *Conn {
	return &Conn{
		Conn:   conn,
		useAES: useAES,
	}
}

func (c *Conn) Write(payload []byte) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	totalLength := len(payload)
	outBytes := make([]byte, 5+8192+16)
	for offset := 0; offset < totalLength; {
		chunk := payload[offset:]
		if len(chunk) > 8192 {
			chunk = chunk[:8192]
		}
		offset += len(chunk)
		headerAndData := outBytes[:5+len(chunk)+16]
		encodeHeader(headerAndData, len(chunk)+16)
		atMaxNonce := bytes.Equal(c.aead.nonce[:], maxNonce)
		c.aead.seal(headerAndData[:5], nil, chunk, headerAndData[:5])
		if atMaxNonce {
			c.aead = newAEAD(headerAndData, c.unitedKey, c.useAES)
		}
		if c.preWrite != nil {
			headerAndData = append(c.preWrite, headerAndData...)
			c.preWrite = nil
		}
		if _, err := c.Conn.Write(headerAndData); err != nil {
			return 0, err
		}
	}
	return totalLength, nil
}

func (c *Conn) Read(payload []byte) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	if c.peerAEAD == nil {
		serverRandom := make([]byte, 16)
		if _, err := io.ReadFull(c.Conn, serverRandom); err != nil {
			return 0, err
		}
		c.peerAEAD = newAEAD(serverRandom, c.unitedKey, c.useAES)
		if xorConn, ok := c.Conn.(*xorConn); ok {
			xorConn.peerCTR = newCTR(c.unitedKey, serverRandom)
		}
	}
	if c.peerPadding != nil {
		if _, err := io.ReadFull(c.Conn, c.peerPadding); err != nil {
			return 0, err
		}
		if _, err := c.peerAEAD.open(c.peerPadding[:0], nil, c.peerPadding, nil); err != nil {
			return 0, err
		}
		c.peerPadding = nil
	}
	if c.input.Len() > 0 {
		return c.input.Read(payload)
	}

	var peerHeader [5]byte
	if _, err := io.ReadFull(c.Conn, peerHeader[:]); err != nil {
		return 0, err
	}
	length, err := decodeHeader(peerHeader[:])
	if err != nil {
		if c.client != nil && errors.Is(err, errInvalidHeader) {
			c.client.access.Lock()
			if bytes.HasPrefix(c.unitedKey, c.client.pfsKey) {
				c.client.expire = time.Now()
			}
			c.client.access.Unlock()
			return 0, errors.New("VLESS encryption ticket expired; retry connection")
		}
		return 0, err
	}
	c.client = nil
	if c.rawInput.Cap() < length {
		c.rawInput.Grow(length)
	}
	peerData := c.rawInput.Bytes()[:length]
	if _, err := io.ReadFull(c.Conn, peerData); err != nil {
		return 0, err
	}
	plain := peerData[:length-16]
	if len(plain) <= len(payload) {
		plain = payload[:len(plain)]
	}
	var nextAEAD *aeadState
	if bytes.Equal(c.peerAEAD.nonce[:], maxNonce) {
		nextAEAD = newAEAD(append(peerHeader[:], peerData...), c.unitedKey, c.useAES)
	}
	if _, err := c.peerAEAD.open(plain[:0], nil, peerData, peerHeader[:]); err != nil {
		return 0, err
	}
	if nextAEAD != nil {
		c.peerAEAD = nextAEAD
	}
	if len(plain) > len(payload) {
		c.input.Reset(plain[copy(payload, plain):])
		plain = payload
	}
	return len(plain), nil
}

type aeadState struct {
	cipher.AEAD
	nonce [12]byte
}

func newAEAD(context, key []byte, useAES bool) *aeadState {
	derivedKey := make([]byte, 32)
	blake3.DeriveKey(string(context), key, derivedKey)
	var aead cipher.AEAD
	if useAES {
		block, _ := aes.NewCipher(derivedKey)
		aead, _ = cipher.NewGCM(block)
	} else {
		aead, _ = chacha20poly1305.New(derivedKey)
	}
	return &aeadState{AEAD: aead}
}

func (a *aeadState) seal(dst, nonce, plaintext, additionalData []byte) []byte {
	if nonce == nil {
		nonce = increaseNonce(a.nonce[:])
	}
	return a.AEAD.Seal(dst, nonce, plaintext, additionalData)
}

func (a *aeadState) open(dst, nonce, ciphertext, additionalData []byte) ([]byte, error) {
	if nonce == nil {
		nonce = increaseNonce(a.nonce[:])
	}
	return a.AEAD.Open(dst, nonce, ciphertext, additionalData)
}

func increaseNonce(nonce []byte) []byte {
	for index := 0; index < len(nonce); index++ {
		nonce[len(nonce)-1-index]++
		if nonce[len(nonce)-1-index] != 0 {
			break
		}
	}
	return nonce
}

var maxNonce = bytes.Repeat([]byte{255}, 12)

func encodeLength(length int) []byte {
	return []byte{byte(length >> 8), byte(length)}
}

func decodeLength(buffer []byte) int {
	return int(buffer[0])<<8 | int(buffer[1])
}

func encodeHeader(header []byte, length int) {
	header[0] = 23
	header[1] = 3
	header[2] = 3
	header[3] = byte(length >> 8)
	header[4] = byte(length)
}

var errInvalidHeader = errors.New("invalid VLESS encryption header")

func decodeHeader(header []byte) (int, error) {
	length := int(header[3])<<8 | int(header[4])
	if header[0] != 23 || header[1] != 3 || header[2] != 3 {
		length = 0
	}
	if length < 17 || length > 17000 {
		return 0, fmt.Errorf("%w: %v", errInvalidHeader, header[:5])
	}
	return length, nil
}

func parsePadding(padding string, paddingLengths, paddingGaps *[][3]int) error {
	if padding == "" {
		return nil
	}
	maxLength := 0
	for index, value := range strings.Split(padding, ".") {
		fields := strings.Split(value, "-")
		if len(fields) < 3 || fields[0] == "" || fields[1] == "" || fields[2] == "" {
			return fmt.Errorf("invalid padding length/gap parameter %q", value)
		}
		parsed := [3]int{}
		var err error
		for fieldIndex := range parsed {
			parsed[fieldIndex], err = strconv.Atoi(fields[fieldIndex])
			if err != nil {
				return err
			}
		}
		if index == 0 && (parsed[0] < 100 || parsed[1] < 35 || parsed[2] < 35) {
			return errors.New("first padding length must not be smaller than 35")
		}
		if index%2 == 0 {
			*paddingLengths = append(*paddingLengths, parsed)
			maxLength += max(parsed[1], parsed[2])
		} else {
			*paddingGaps = append(*paddingGaps, parsed)
		}
	}
	if maxLength > 18+65535 {
		return errors.New("total padding length must not be larger than 65553")
	}
	return nil
}

func createPadding(paddingLengths, paddingGaps [][3]int) (length int, lengths []int, gaps []time.Duration) {
	if len(paddingLengths) == 0 {
		paddingLengths = [][3]int{{100, 111, 1111}, {50, 0, 3333}}
		paddingGaps = [][3]int{{75, 0, 111}}
	}
	for _, value := range paddingLengths {
		paddingLength := 0
		if value[0] >= int(randBetween(0, 100)) {
			paddingLength = int(randBetween(int64(value[1]), int64(value[2])))
		}
		lengths = append(lengths, paddingLength)
		length += paddingLength
	}
	for _, value := range paddingGaps {
		gap := 0
		if value[0] >= int(randBetween(0, 100)) {
			gap = int(randBetween(int64(value[1]), int64(value[2])))
		}
		gaps = append(gaps, time.Duration(gap)*time.Millisecond)
	}
	return
}

func randBetween(from, to int64) int64 {
	if from == to {
		return from
	}
	if to < from {
		from, to = to, from
	}
	return from + mathrand.Int64N(to-from)
}
