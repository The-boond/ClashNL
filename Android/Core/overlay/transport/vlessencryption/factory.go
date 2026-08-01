// Package vlessencryption implements the client side of VLESS post-quantum
// encryption used by current Xray/Mihomo profiles.
//
// This code is adapted from MetaCubeX/mihomo's GPL-3.0-or-later
// transport/vless/encryption package.
package vlessencryption

import (
	"crypto/mlkem"
	"encoding/base64"
	"fmt"
	"strings"
)

const (
	x25519PasswordSize   = 32
	mlkem768ClientLength = mlkem.EncapsulationKeySize768
)

// NewClient parses a VLESS encryption value. A nil client means that the
// profile uses the traditional unencrypted VLESS framing.
func NewClient(encryption string) (*Client, error) {
	switch encryption {
	case "", "none":
		return nil, nil
	}

	parts := strings.Split(encryption, ".")
	if len(parts) < 4 || parts[0] != "mlkem768x25519plus" {
		return nil, fmt.Errorf("invalid VLESS encryption value")
	}

	var xorMode uint32
	switch parts[1] {
	case "native":
	case "xorpub":
		xorMode = 1
	case "random":
		xorMode = 2
	default:
		return nil, fmt.Errorf("invalid VLESS encryption mode %q", parts[1])
	}

	var seconds uint32
	switch parts[2] {
	case "1rtt":
	case "0rtt":
		seconds = 1
	default:
		return nil, fmt.Errorf("invalid VLESS encryption handshake %q", parts[2])
	}

	var publicKeys [][]byte
	var paddings []string
	for _, value := range parts[3:] {
		if len(value) < 20 {
			paddings = append(paddings, value)
			continue
		}
		key, err := base64.RawURLEncoding.DecodeString(value)
		if err != nil {
			return nil, fmt.Errorf("invalid VLESS encryption key encoding")
		}
		if len(key) != x25519PasswordSize && len(key) != mlkem768ClientLength {
			return nil, fmt.Errorf("invalid VLESS encryption key length %d", len(key))
		}
		publicKeys = append(publicKeys, key)
	}

	client := new(Client)
	if err := client.init(publicKeys, xorMode, seconds, strings.Join(paddings, ".")); err != nil {
		return nil, fmt.Errorf("initialize VLESS encryption: %w", err)
	}
	return client, nil
}
