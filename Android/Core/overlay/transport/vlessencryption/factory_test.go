package vlessencryption

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"testing"
)

func TestNewClient(t *testing.T) {
	disabled, err := NewClient("none")
	if err != nil || disabled != nil {
		t.Fatalf("disabled encryption: client=%v err=%v", disabled, err)
	}

	privateKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	publicKey := base64.RawURLEncoding.EncodeToString(privateKey.PublicKey().Bytes())
	client, err := NewClient("mlkem768x25519plus.native.0rtt." + publicKey)
	if err != nil {
		t.Fatal(err)
	}
	if client == nil || len(client.publicKeys) != 1 {
		t.Fatalf("unexpected parsed client: %#v", client)
	}
}

func TestNewClientRejectsInvalidValue(t *testing.T) {
	if _, err := NewClient("mlkem768x25519plus.invalid.0rtt.fixture"); err == nil {
		t.Fatal("invalid mode was accepted")
	}
}
