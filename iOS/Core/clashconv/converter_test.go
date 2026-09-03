package clashconv

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestConvertModernProtocols(t *testing.T) {
	input := `
ipv6: false
mode: global
log-level: info
dns:
  enable: true
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  nameserver:
    - https://1.1.1.1/dns-query
proxies:
  - name: reality
    type: vless
    server: example.com
    port: 443
    uuid: 11111111-1111-1111-1111-111111111111
    network: grpc
    tls: true
    servername: www.example.com
    client-fingerprint: chrome
    reality-opts:
      public-key: HtNpnLgHDsYBvlyw5eS1GklGAqfD0L6gElTbaBzTEHY
      short-id: "01234567"
    grpc-opts:
      grpc-service-name: tunnel
  - name: hy2
    type: hysteria2
    server: 192.0.2.2
    port: 443
    password: secret
    obfs: salamander
    obfs-password: obfs-secret
    skip-cert-verify: true
  - name: tuic
    type: tuic
    server: 192.0.2.3
    port: 443
    uuid: 22222222-2222-2222-2222-222222222222
    password: secret
    congestion-controller: bbr
    reduce-rtt: true
  - name: anytls
    type: anytls
    server: 192.0.2.4
    port: 443
    password: secret
proxy-groups:
  - name: Proxy
    type: select
    proxies: [reality, hy2, tuic, anytls, DIRECT]
rules:
  - DOMAIN-SUFFIX,example.com,Proxy
  - GEOIP,CN,DIRECT
  - MATCH,Proxy
`
	output, err := Convert(input)
	if err != nil {
		t.Fatal(err)
	}
	var document map[string]any
	if err := json.Unmarshal([]byte(output), &document); err != nil {
		t.Fatal(err)
	}
	outbounds := document["outbounds"].([]any)
	if len(outbounds) != 7 {
		t.Fatalf("expected 7 outbounds, got %d", len(outbounds))
	}
	hasDirect := false
	for _, rawOutbound := range outbounds {
		outbound, ok := rawOutbound.(map[string]any)
		if ok && outbound["tag"] == "direct" && outbound["type"] == "direct" {
			hasDirect = true
			break
		}
	}
	if !hasDirect {
		t.Fatal("diagnostic direct outbound missing")
	}
	route, ok := document["route"].(map[string]any)
	if !ok || route["auto_detect_interface"] != true {
		t.Fatal("route must auto-detect the physical interface for direct diagnostics")
	}
	if !strings.Contains(output, `"type": "hysteria2"`) {
		t.Fatal("Hysteria2 outbound missing")
	}
	if !strings.Contains(output, `"public_key": "HtNpnLgHDsYBvlyw5eS1GklGAqfD0L6gElTbaBzTEHY"`) {
		t.Fatal("Reality options missing")
	}
	if !strings.Contains(output, `"type": "fakeip"`) {
		t.Fatal("FakeIP DNS server missing")
	}
	if !strings.Contains(output, `"default_mode": "Global"`) {
		t.Fatal("Clash mode was not preserved")
	}
	if !strings.Contains(output, `"tag": "geoip-cn"`) {
		t.Fatal("GeoIP rule set missing")
	}
}

func TestConvertWireGuardEndpoint(t *testing.T) {
	input := `
proxies:
  - name: wg
    type: wireguard
    server: 192.0.2.1
    port: 51820
    ip: 10.0.0.2
    private-key: private
    public-key: public
    reserved: [1, 2, 3]
rules:
  - MATCH,wg
`
	output, err := Convert(input)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output, `"endpoints"`) || !strings.Contains(output, `"private_key": "private"`) {
		t.Fatalf("WireGuard endpoint missing:\n%s", output)
	}
}

func TestUnsupportedProtocolIsReported(t *testing.T) {
	input := `
proxies:
  - name: unsupported
    type: imaginary
    server: 192.0.2.1
    port: 443
`
	_, err := Convert(input)
	if err == nil || !strings.Contains(err.Error(), "unsupported protocol") {
		t.Fatalf("expected unsupported protocol error, got %v", err)
	}
}

func TestDNSBootstrapAndDurationUnits(t *testing.T) {
	input := `
dns:
  default-nameserver: [1.1.1.1]
  nameserver:
    - https://dns.example/dns-query
proxies:
  - name: hy2
    type: hysteria2
    server: 192.0.2.2
    port: 443
    password: secret
    hop-interval: 10
  - name: tuic
    type: tuic
    server: 192.0.2.3
    port: 443
    uuid: 22222222-2222-2222-2222-222222222222
    password: secret
    heartbeat-interval: 5000
proxy-groups:
  - name: Proxy
    type: select
    expected-status: 204
    proxies: [hy2, tuic]
rules:
  - MATCH,Proxy
`
	output, err := Convert(input)
	if err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{
		`"tag": "dns-bootstrap"`,
		`"domain_resolver": "dns-bootstrap"`,
		`"hop_interval": "10s"`,
		`"heartbeat": "5000ms"`,
	} {
		if !strings.Contains(output, expected) {
			t.Fatalf("missing %s:\n%s", expected, output)
		}
	}
	if strings.Contains(output, `"default": "204"`) {
		t.Fatalf("selector expected-status must not be treated as a default outbound:\n%s", output)
	}
}
