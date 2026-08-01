// Adapted from MetaCubeX/mihomo (GPL-3.0-or-later).
package vlessencryption

import (
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/mlkem"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"runtime"
	"sync"
	"time"

	"github.com/zeebo/blake3"
	"golang.org/x/sys/cpu"
)

var (
	hasGCMAsmAMD64 = cpu.X86.HasAES && cpu.X86.HasPCLMULQDQ && cpu.X86.HasSSE41 && cpu.X86.HasSSSE3
	hasGCMAsmARM64 = cpu.ARM64.HasAES && cpu.ARM64.HasPMULL
	hasGCMAsmS390X = cpu.S390X.HasAES && cpu.S390X.HasAESCTR && cpu.S390X.HasGHASH
	hasGCMAsmPPC64 = runtime.GOARCH == "ppc64" || runtime.GOARCH == "ppc64le"

	hasAESGCMHardwareSupport = hasGCMAsmAMD64 || hasGCMAsmARM64 || hasGCMAsmS390X || hasGCMAsmPPC64
)

type Client struct {
	publicKeys     []any
	publicKeyBytes [][]byte
	hashes         [][32]byte
	relaysLength   int
	xorMode        uint32
	seconds        uint32
	paddingLengths [][3]int
	paddingGaps    [][3]int

	access sync.RWMutex
	expire time.Time
	pfsKey []byte
	ticket []byte
}

func (c *Client) init(publicKeyBytes [][]byte, xorMode, seconds uint32, padding string) (err error) {
	if c.publicKeys != nil {
		return errors.New("already initialized")
	}
	if len(publicKeyBytes) == 0 {
		return errors.New("empty public keys")
	}
	c.publicKeys = make([]any, len(publicKeyBytes))
	c.publicKeyBytes = publicKeyBytes
	c.hashes = make([][32]byte, len(publicKeyBytes))
	for index, key := range publicKeyBytes {
		if len(key) == x25519PasswordSize {
			c.publicKeys[index], err = ecdh.X25519().NewPublicKey(key)
			if err != nil {
				return err
			}
			c.relaysLength += 32 + 32
		} else {
			c.publicKeys[index], err = mlkem.NewEncapsulationKey768(key)
			if err != nil {
				return err
			}
			c.relaysLength += mlkem.CiphertextSize768 + 32
		}
		c.hashes[index] = blake3.Sum256(key)
	}
	c.relaysLength -= 32
	c.xorMode = xorMode
	c.seconds = seconds
	return parsePadding(padding, &c.paddingLengths, &c.paddingGaps)
}

// Handshake wraps conn with VLESS post-quantum encryption.
func (c *Client) Handshake(conn net.Conn) (*Conn, error) {
	if c.publicKeys == nil {
		return nil, errors.New("uninitialized VLESS encryption client")
	}
	encryptedConn := newConn(conn, hasAESGCMHardwareSupport)

	ivAndRelaysLength := 16 + c.relaysLength
	pfsKeyExchangeLength := 18 + mlkem.EncapsulationKeySize768 + 32 + 16
	paddingLength, paddingLengths, paddingGaps := createPadding(c.paddingLengths, c.paddingGaps)
	clientHello := make([]byte, ivAndRelaysLength+pfsKeyExchangeLength+paddingLength)

	iv := clientHello[:16]
	if _, err := rand.Read(iv); err != nil {
		return nil, err
	}
	relays := clientHello[16:ivAndRelaysLength]
	var nonForwardSecureKey []byte
	var lastCTR cipher.Stream
	for index, key := range c.publicKeys {
		keyLength := 32
		switch typedKey := key.(type) {
		case *ecdh.PublicKey:
			privateKey, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				return nil, err
			}
			copy(relays, privateKey.PublicKey().Bytes())
			nonForwardSecureKey, err = privateKey.ECDH(typedKey)
			if err != nil {
				return nil, err
			}
		case *mlkem.EncapsulationKey768:
			var ciphertext []byte
			nonForwardSecureKey, ciphertext = typedKey.Encapsulate()
			copy(relays, ciphertext)
			keyLength = mlkem.CiphertextSize768
		}
		if c.xorMode > 0 {
			newCTR(c.publicKeyBytes[index], iv).XORKeyStream(relays, relays[:keyLength])
		}
		if lastCTR != nil {
			lastCTR.XORKeyStream(relays, relays[:32])
		}
		if index == len(c.publicKeys)-1 {
			break
		}
		lastCTR = newCTR(nonForwardSecureKey, iv)
		lastCTR.XORKeyStream(relays[keyLength:], c.hashes[index+1][:])
		relays = relays[keyLength+32:]
	}
	nfsAEAD := newAEAD(iv, nonForwardSecureKey, encryptedConn.useAES)

	if c.seconds > 0 {
		c.access.RLock()
		if time.Now().Before(c.expire) {
			encryptedConn.client = c
			encryptedConn.unitedKey = append(c.pfsKey, nonForwardSecureKey...)
			nfsAEAD.seal(clientHello[:ivAndRelaysLength], nil, encodeLength(32), nil)
			nfsAEAD.seal(clientHello[:ivAndRelaysLength+18], nil, c.ticket, nil)
			c.access.RUnlock()
			encryptedConn.preWrite = clientHello[:ivAndRelaysLength+18+32]
			encryptedConn.aead = newAEAD(
				clientHello[ivAndRelaysLength+18:ivAndRelaysLength+18+32],
				encryptedConn.unitedKey,
				encryptedConn.useAES,
			)
			if c.xorMode == 2 {
				encryptedConn.Conn = newXORConn(conn, newCTR(encryptedConn.unitedKey, iv), nil, len(encryptedConn.preWrite), 16)
			}
			return encryptedConn, nil
		}
		c.access.RUnlock()
	}

	pfsKeyExchange := clientHello[ivAndRelaysLength : ivAndRelaysLength+pfsKeyExchangeLength]
	nfsAEAD.seal(pfsKeyExchange[:0], nil, encodeLength(pfsKeyExchangeLength-18), nil)
	mlkemPrivateKey, err := mlkem.GenerateKey768()
	if err != nil {
		return nil, err
	}
	x25519PrivateKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	pfsPublicKey := append(mlkemPrivateKey.EncapsulationKey().Bytes(), x25519PrivateKey.PublicKey().Bytes()...)
	nfsAEAD.seal(pfsKeyExchange[:18], nil, pfsPublicKey, nil)

	padding := clientHello[ivAndRelaysLength+pfsKeyExchangeLength:]
	nfsAEAD.seal(padding[:0], nil, encodeLength(paddingLength-18), nil)
	nfsAEAD.seal(padding[:18], nil, padding[18:paddingLength-16], nil)

	paddingLengths[0] = ivAndRelaysLength + pfsKeyExchangeLength + paddingLengths[0]
	for index, length := range paddingLengths {
		if length > 0 {
			if _, err := conn.Write(clientHello[:length]); err != nil {
				return nil, err
			}
			clientHello = clientHello[length:]
		}
		if len(paddingGaps) > index {
			time.Sleep(paddingGaps[index])
		}
	}

	encryptedPfsPublicKey := make([]byte, mlkem.CiphertextSize768+32+16)
	if _, err := io.ReadFull(conn, encryptedPfsPublicKey); err != nil {
		return nil, err
	}
	if _, err := nfsAEAD.open(encryptedPfsPublicKey[:0], maxNonce, encryptedPfsPublicKey, nil); err != nil {
		return nil, err
	}
	mlkemKey, err := mlkemPrivateKey.Decapsulate(encryptedPfsPublicKey[:mlkem.CiphertextSize768])
	if err != nil {
		return nil, err
	}
	peerX25519PublicKey, err := ecdh.X25519().NewPublicKey(
		encryptedPfsPublicKey[mlkem.CiphertextSize768 : mlkem.CiphertextSize768+32],
	)
	if err != nil {
		return nil, err
	}
	x25519Key, err := x25519PrivateKey.ECDH(peerX25519PublicKey)
	if err != nil {
		return nil, err
	}
	pfsKey := make([]byte, 64)
	copy(pfsKey, mlkemKey)
	copy(pfsKey[32:], x25519Key)
	encryptedConn.unitedKey = append(pfsKey, nonForwardSecureKey...)
	encryptedConn.aead = newAEAD(pfsPublicKey, encryptedConn.unitedKey, encryptedConn.useAES)
	encryptedConn.peerAEAD = newAEAD(
		encryptedPfsPublicKey[:mlkem.CiphertextSize768+32],
		encryptedConn.unitedKey,
		encryptedConn.useAES,
	)

	encryptedTicket := make([]byte, 32)
	if _, err := io.ReadFull(conn, encryptedTicket); err != nil {
		return nil, err
	}
	if _, err := encryptedConn.peerAEAD.open(encryptedTicket[:0], nil, encryptedTicket, nil); err != nil {
		return nil, err
	}
	seconds := decodeLength(encryptedTicket)
	if c.seconds > 0 && seconds > 0 {
		c.access.Lock()
		c.expire = time.Now().Add(time.Duration(seconds) * time.Second)
		c.pfsKey = pfsKey
		c.ticket = encryptedTicket[:16]
		c.access.Unlock()
	}

	encryptedLength := make([]byte, 18)
	if _, err := io.ReadFull(conn, encryptedLength); err != nil {
		return nil, err
	}
	if _, err := encryptedConn.peerAEAD.open(encryptedLength[:0], nil, encryptedLength, nil); err != nil {
		return nil, err
	}
	length := decodeLength(encryptedLength[:2])
	encryptedConn.peerPadding = make([]byte, length)
	if c.xorMode == 2 {
		encryptedConn.Conn = newXORConn(
			conn,
			newCTR(encryptedConn.unitedKey, iv),
			newCTR(encryptedConn.unitedKey, encryptedTicket[:16]),
			0,
			length,
		)
	}
	return encryptedConn, nil
}
