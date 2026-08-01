package clashconv

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

const defaultURLTestURL = "https://www.gstatic.com/generate_204"

type conversionState struct {
	tags        map[string]struct{}
	ruleSets    []map[string]any
	ruleSetTags map[string]struct{}
	providers   map[string]any
}

type nodeResult struct {
	outbound map[string]any
	endpoint map[string]any
}

// Convert converts the commonly used Clash/Mihomo YAML profile format into a
// sing-box JSON configuration suitable for the Apple packet-tunnel client.
//
// The converter is intentionally strict: a construct that cannot be preserved
// is reported instead of being silently ignored.
func Convert(content string) (string, error) {
	var decoded any
	if err := yaml.Unmarshal([]byte(content), &decoded); err != nil {
		return "", fmt.Errorf("decode Clash YAML: %w", err)
	}
	root, ok := normalizeYAML(decoded).(map[string]any)
	if !ok {
		return "", fmt.Errorf("Clash profile root must be a mapping")
	}

	proxies, err := mapList(root["proxies"], "proxies")
	if err != nil {
		return "", err
	}
	groups, err := mapList(root["proxy-groups"], "proxy-groups")
	if err != nil {
		return "", err
	}
	if len(proxies) == 0 {
		return "", fmt.Errorf("Clash profile contains no proxies")
	}
	clashMode, err := convertClashMode(root)
	if err != nil {
		return "", err
	}

	state := &conversionState{
		tags:        map[string]struct{}{"direct": {}, "block": {}},
		ruleSetTags: make(map[string]struct{}),
		providers:   stringMap(root["rule-providers"]),
	}

	for _, proxy := range proxies {
		if err := state.reserveTag(requiredText(proxy, "name", "proxy")); err != nil {
			return "", err
		}
	}
	for _, group := range groups {
		if err := state.reserveTag(requiredText(group, "name", "proxy group")); err != nil {
			return "", err
		}
	}

	outbounds := make([]map[string]any, 0, len(proxies)+len(groups)+2)
	endpoints := make([]map[string]any, 0)
	for _, proxy := range proxies {
		result, convertErr := convertProxy(proxy)
		if convertErr != nil {
			return "", convertErr
		}
		if result.outbound != nil {
			outbounds = append(outbounds, result.outbound)
		}
		if result.endpoint != nil {
			endpoints = append(endpoints, result.endpoint)
		}
	}

	for _, group := range groups {
		outbound, convertErr := state.convertGroup(group)
		if convertErr != nil {
			return "", convertErr
		}
		outbounds = append(outbounds, outbound)
	}
	outbounds = append(outbounds,
		map[string]any{"type": "direct", "tag": "direct"},
		map[string]any{"type": "block", "tag": "block"},
	)

	dnsOptions, resolverTag, fakeIP := convertDNS(root)
	routeRules := []map[string]any{
		{"action": "sniff"},
		{"protocol": "dns", "action": "hijack-dns"},
	}

	finalTag := defaultFinalTag(groups, proxies)
	clashRules, err := stringListStrict(root["rules"], "rules")
	if err != nil {
		return "", err
	}
	for index, ruleText := range clashRules {
		rule, final, convertErr := state.convertRule(ruleText)
		if convertErr != nil {
			return "", fmt.Errorf("rule %d (%q): %w", index+1, ruleText, convertErr)
		}
		if final != "" {
			finalTag = final
			continue
		}
		if rule != nil {
			routeRules = append(routeRules, rule)
		}
	}
	if _, exists := state.tags[finalTag]; !exists {
		return "", fmt.Errorf("final outbound %q does not exist", finalTag)
	}

	ipv6 := boolValue(root["ipv6"], false)
	tunAddresses := []string{"172.19.0.1/30"}
	if ipv6 {
		tunAddresses = append(tunAddresses, "fdfe:dcba:9876::1/126")
	}
	tunInbound := map[string]any{
		"type":         "tun",
		"tag":          "tun-in",
		"address":      tunAddresses,
		"auto_route":   true,
		"strict_route": true,
		"stack":        "mixed",
	}
	if fakeIP {
		tunInbound["dns_mode"] = "hijack"
	}

	route := map[string]any{
		"rules":                 routeRules,
		"final":                 finalTag,
		"auto_detect_interface": true,
	}
	if resolverTag != "" {
		route["default_domain_resolver"] = resolverTag
	}
	if len(state.ruleSets) != 0 {
		route["rule_set"] = state.ruleSets
	}

	level := strings.ToLower(textValue(root["log-level"]))
	switch level {
	case "trace", "debug", "info", "warn", "error", "fatal", "panic":
	default:
		level = "info"
	}

	document := map[string]any{
		"log": map[string]any{
			"level":     level,
			"timestamp": true,
		},
		"dns":       dnsOptions,
		"inbounds":  []map[string]any{tunInbound},
		"outbounds": outbounds,
		"route":     route,
		"experimental": map[string]any{
			"cache_file": map[string]any{
				"enabled":   true,
				"store_dns": true,
			},
			"clash_api": map[string]any{
				"default_mode": clashMode,
			},
		},
	}
	if len(endpoints) != 0 {
		document["endpoints"] = endpoints
	}

	encoded, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return "", fmt.Errorf("encode sing-box JSON: %w", err)
	}
	return string(encoded) + "\n", nil
}

func convertClashMode(root map[string]any) (string, error) {
	switch strings.ToLower(textValue(root["mode"])) {
	case "", "rule":
		return "Rule", nil
	case "global":
		return "Global", nil
	case "direct":
		return "Direct", nil
	default:
		return "", fmt.Errorf("unsupported Clash mode %q", textValue(root["mode"]))
	}
}

func (s *conversionState) reserveTag(tag string) error {
	if tag == "" {
		return fmt.Errorf("empty outbound tag")
	}
	if strings.EqualFold(tag, "direct") || strings.EqualFold(tag, "block") {
		return fmt.Errorf("outbound name %q is reserved", tag)
	}
	if _, exists := s.tags[tag]; exists {
		return fmt.Errorf("duplicate outbound name %q", tag)
	}
	s.tags[tag] = struct{}{}
	return nil
}

func convertProxy(proxy map[string]any) (nodeResult, error) {
	name := requiredText(proxy, "name", "proxy")
	proxyType := strings.ToLower(requiredText(proxy, "type", "proxy "+name))
	if name == "" || proxyType == "" {
		return nodeResult{}, fmt.Errorf("proxy requires name and type")
	}

	if proxyType == "wireguard" {
		endpoint, err := convertWireGuard(proxy, name)
		return nodeResult{endpoint: endpoint}, err
	}

	server, err := requiredServer(proxy, name)
	if err != nil {
		return nodeResult{}, err
	}
	port, err := requiredPort(proxy, name)
	if err != nil {
		return nodeResult{}, err
	}
	outbound := map[string]any{
		"tag":         name,
		"server":      server,
		"server_port": port,
	}
	applyDialOptions(proxy, outbound)

	switch proxyType {
	case "ss", "shadowsocks":
		outbound["type"] = "shadowsocks"
		outbound["method"] = requiredText(proxy, "cipher", "Shadowsocks "+name)
		outbound["password"] = requiredText(proxy, "password", "Shadowsocks "+name)
		if textValue(proxy["plugin"]) != "" {
			plugin := textValue(proxy["plugin"])
			if plugin == "obfs" {
				plugin = "obfs-local"
			}
			outbound["plugin"] = plugin
			if opts := stringMap(proxy["plugin-opts"]); len(opts) != 0 {
				outbound["plugin_opts"] = flattenPluginOptions(opts)
			}
		}
	case "vmess":
		outbound["type"] = "vmess"
		outbound["uuid"] = requiredText(proxy, "uuid", "VMess "+name)
		security := textValue(proxy["cipher"])
		if security == "" {
			security = "auto"
		}
		outbound["security"] = security
		if alterID, ok := intValue(proxy["alterId"]); ok {
			outbound["alter_id"] = alterID
		} else if alterID, ok := intValue(proxy["alter-id"]); ok {
			outbound["alter_id"] = alterID
		}
		if err := applyTLS(proxy, outbound, false); err != nil {
			return nodeResult{}, fmt.Errorf("VMess %q: %w", name, err)
		}
		if err := applyTransport(proxy, outbound); err != nil {
			return nodeResult{}, fmt.Errorf("VMess %q: %w", name, err)
		}
	case "vless":
		outbound["type"] = "vless"
		outbound["uuid"] = requiredText(proxy, "uuid", "VLESS "+name)
		copyText(proxy, outbound, "flow", "flow")
		copyText(proxy, outbound, "encryption", "encryption")
		copyText(proxy, outbound, "packet-encoding", "packet_encoding")
		if err := applyTLS(proxy, outbound, false); err != nil {
			return nodeResult{}, fmt.Errorf("VLESS %q: %w", name, err)
		}
		if err := applyTransport(proxy, outbound); err != nil {
			return nodeResult{}, fmt.Errorf("VLESS %q: %w", name, err)
		}
	case "trojan":
		outbound["type"] = "trojan"
		outbound["password"] = requiredText(proxy, "password", "Trojan "+name)
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("Trojan %q: %w", name, err)
		}
		if err := applyTransport(proxy, outbound); err != nil {
			return nodeResult{}, fmt.Errorf("Trojan %q: %w", name, err)
		}
	case "hysteria":
		outbound["type"] = "hysteria"
		if auth := firstText(proxy, "auth-str", "auth_str", "auth"); auth != "" {
			outbound["auth_str"] = auth
		}
		copyBandwidth(proxy, outbound)
		copyText(proxy, outbound, "obfs", "obfs")
		copyStringList(proxy, outbound, "ports", "server_ports")
		copyDuration(proxy, outbound, "hop-interval", "hop_interval", "s")
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("Hysteria %q: %w", name, err)
		}
	case "hysteria2", "hy2":
		outbound["type"] = "hysteria2"
		password := firstText(proxy, "password", "auth", "auth-str")
		if password == "" {
			return nodeResult{}, fmt.Errorf("Hysteria2 %q: missing password/auth", name)
		}
		outbound["password"] = password
		copyBandwidth(proxy, outbound)
		copyStringList(proxy, outbound, "ports", "server_ports")
		copyStringList(proxy, outbound, "server-ports", "server_ports")
		copyDuration(proxy, outbound, "hop-interval", "hop_interval", "s")
		if obfsType := textValue(proxy["obfs"]); obfsType != "" && obfsType != "none" {
			outbound["obfs"] = map[string]any{
				"type":     obfsType,
				"password": firstText(proxy, "obfs-password", "obfs_password"),
			}
		}
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("Hysteria2 %q: %w", name, err)
		}
	case "tuic":
		outbound["type"] = "tuic"
		outbound["uuid"] = requiredText(proxy, "uuid", "TUIC "+name)
		outbound["password"] = requiredText(proxy, "password", "TUIC "+name)
		copyText(proxy, outbound, "congestion-controller", "congestion_control")
		copyText(proxy, outbound, "udp-relay-mode", "udp_relay_mode")
		copyDuration(proxy, outbound, "heartbeat-interval", "heartbeat", "ms")
		if boolValue(proxy["reduce-rtt"], false) {
			outbound["zero_rtt_handshake"] = true
		}
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("TUIC %q: %w", name, err)
		}
	case "anytls":
		outbound["type"] = "anytls"
		outbound["password"] = requiredText(proxy, "password", "AnyTLS "+name)
		copyDuration(proxy, outbound, "idle-session-check-interval", "idle_session_check_interval", "s")
		copyDuration(proxy, outbound, "idle-session-timeout", "idle_session_timeout", "s")
		copyInt(proxy, outbound, "min-idle-session", "min_idle_session")
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("AnyTLS %q: %w", name, err)
		}
	case "http", "https":
		outbound["type"] = "http"
		copyText(proxy, outbound, "username", "username")
		copyText(proxy, outbound, "password", "password")
		if err := applyTLS(proxy, outbound, proxyType == "https"); err != nil {
			return nodeResult{}, fmt.Errorf("HTTP %q: %w", name, err)
		}
	case "socks", "socks5":
		outbound["type"] = "socks"
		outbound["version"] = "5"
		copyText(proxy, outbound, "username", "username")
		copyText(proxy, outbound, "password", "password")
	case "snell":
		outbound["type"] = "snell"
		outbound["psk"] = requiredText(proxy, "psk", "Snell "+name)
		version, ok := intValue(proxy["version"])
		if !ok {
			version = 4
		}
		outbound["version"] = version
		if opts := stringMap(proxy["obfs-opts"]); len(opts) != 0 {
			copyText(opts, outbound, "mode", "obfs_mode")
			copyText(opts, outbound, "host", "obfs_host")
		}
	case "naive":
		outbound["type"] = "naive"
		copyText(proxy, outbound, "username", "username")
		outbound["password"] = requiredText(proxy, "password", "Naive "+name)
		if boolValue(proxy["quic"], false) {
			outbound["quic"] = true
		}
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("Naive %q: %w", name, err)
		}
	case "shadowtls":
		outbound["type"] = "shadowtls"
		version, ok := intValue(proxy["version"])
		if !ok {
			version = 3
		}
		outbound["version"] = version
		copyText(proxy, outbound, "password", "password")
		if err := applyTLS(proxy, outbound, true); err != nil {
			return nodeResult{}, fmt.Errorf("ShadowTLS %q: %w", name, err)
		}
	case "ssh":
		outbound["type"] = "ssh"
		copyText(proxy, outbound, "username", "user")
		copyText(proxy, outbound, "user", "user")
		copyText(proxy, outbound, "password", "password")
		copyStringList(proxy, outbound, "private-key", "private_key")
		copyStringList(proxy, outbound, "host-key", "host_key")
	default:
		return nodeResult{}, fmt.Errorf("proxy %q uses unsupported protocol %q", name, proxyType)
	}

	return nodeResult{outbound: outbound}, nil
}

func convertWireGuard(proxy map[string]any, name string) (map[string]any, error) {
	server, err := requiredServer(proxy, name)
	if err != nil {
		return nil, err
	}
	port, err := requiredPort(proxy, name)
	if err != nil {
		return nil, err
	}
	privateKey := requiredText(proxy, "private-key", "WireGuard "+name)
	publicKey := requiredText(proxy, "public-key", "WireGuard "+name)
	if privateKey == "" || publicKey == "" {
		return nil, fmt.Errorf("WireGuard %q requires private-key and public-key", name)
	}
	addresses := make([]string, 0, 2)
	if ip := textValue(proxy["ip"]); ip != "" {
		addresses = append(addresses, ensurePrefix(ip))
	}
	if ip := textValue(proxy["ipv6"]); ip != "" {
		addresses = append(addresses, ensurePrefix(ip))
	}
	if len(addresses) == 0 {
		return nil, fmt.Errorf("WireGuard %q requires ip or ipv6", name)
	}
	peer := map[string]any{
		"address":     server,
		"port":        port,
		"public_key":  publicKey,
		"allowed_ips": []string{"0.0.0.0/0", "::/0"},
	}
	copyText(proxy, peer, "preshared-key", "pre_shared_key")
	if reserved, ok := intSlice(proxy["reserved"]); ok {
		peer["reserved"] = reserved
	}
	endpoint := map[string]any{
		"type":        "wireguard",
		"tag":         name,
		"address":     addresses,
		"private_key": privateKey,
		"peers":       []map[string]any{peer},
	}
	copyInt(proxy, endpoint, "mtu", "mtu")
	applyDialOptions(proxy, endpoint)
	return endpoint, nil
}

func (s *conversionState) convertGroup(group map[string]any) (map[string]any, error) {
	name := requiredText(group, "name", "proxy group")
	groupType := strings.ToLower(requiredText(group, "type", "proxy group "+name))
	members, err := stringListStrict(group["proxies"], "proxy group "+name+" proxies")
	if err != nil {
		return nil, err
	}
	if len(members) == 0 {
		return nil, fmt.Errorf("proxy group %q has no proxies; provider-only groups are not supported yet", name)
	}
	for index, member := range members {
		members[index] = targetTag(member)
		if _, exists := s.tags[members[index]]; !exists {
			return nil, fmt.Errorf("proxy group %q references unknown outbound %q", name, member)
		}
	}

	outbound := map[string]any{
		"tag":       name,
		"outbounds": members,
	}
	switch groupType {
	case "select":
		outbound["type"] = "selector"
	case "url-test", "fallback":
		outbound["type"] = "urltest"
		testURL := textValue(group["url"])
		if testURL == "" {
			testURL = defaultURLTestURL
		}
		outbound["url"] = testURL
		if interval, ok := intValue(group["interval"]); ok && interval > 0 {
			outbound["interval"] = fmt.Sprintf("%ds", interval)
		}
		if tolerance, ok := intValue(group["tolerance"]); ok && tolerance >= 0 {
			outbound["tolerance"] = tolerance
		}
	case "load-balance", "relay":
		return nil, fmt.Errorf("proxy group %q uses %q, which has no equivalent in the selected core", name, groupType)
	default:
		return nil, fmt.Errorf("proxy group %q uses unsupported type %q", name, groupType)
	}
	return outbound, nil
}

func (s *conversionState) convertRule(ruleText string) (map[string]any, string, error) {
	parts := splitRule(ruleText)
	if len(parts) == 0 {
		return nil, "", nil
	}
	ruleType := strings.ToUpper(parts[0])
	if ruleType == "MATCH" || ruleType == "FINAL" {
		if len(parts) < 2 {
			return nil, "", fmt.Errorf("missing target")
		}
		target := targetTag(parts[1])
		if _, exists := s.tags[target]; !exists {
			return nil, "", fmt.Errorf("unknown target %q", parts[1])
		}
		return nil, target, nil
	}
	if len(parts) < 3 {
		return nil, "", fmt.Errorf("expected TYPE,VALUE,TARGET")
	}
	value := parts[1]
	target := targetTag(parts[2])
	if _, exists := s.tags[target]; !exists {
		return nil, "", fmt.Errorf("unknown target %q", parts[2])
	}
	rule := map[string]any{"outbound": target}

	switch ruleType {
	case "DOMAIN":
		rule["domain"] = value
	case "DOMAIN-SUFFIX":
		rule["domain_suffix"] = strings.TrimPrefix(value, ".")
	case "DOMAIN-KEYWORD":
		rule["domain_keyword"] = value
	case "DOMAIN-REGEX":
		rule["domain_regex"] = value
	case "DOMAIN-WILDCARD":
		rule["domain_regex"] = wildcardRegex(value)
	case "IP-CIDR", "IP-CIDR6":
		rule["ip_cidr"] = value
	case "SRC-IP-CIDR":
		rule["source_ip_cidr"] = value
	case "DST-PORT":
		if err := applyPortRule(rule, value, "port", "port_range"); err != nil {
			return nil, "", err
		}
	case "SRC-PORT":
		if err := applyPortRule(rule, value, "source_port", "source_port_range"); err != nil {
			return nil, "", err
		}
	case "NETWORK":
		rule["network"] = strings.ToLower(value)
	case "PROCESS-NAME":
		rule["process_name"] = value
	case "PROCESS-PATH":
		rule["process_path"] = value
	case "PROCESS-PATH-REGEX":
		rule["process_path_regex"] = value
	case "GEOIP":
		if strings.EqualFold(value, "LAN") || strings.EqualFold(value, "PRIVATE") {
			rule["ip_is_private"] = true
		} else {
			tag := "geoip-" + strings.ToLower(value)
			s.ensureRemoteRuleSet(tag, "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/"+tag+".srs", "binary", "")
			rule["rule_set"] = tag
		}
	case "GEOSITE":
		tag := "geosite-" + strings.ToLower(value)
		s.ensureRemoteRuleSet(tag, "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/"+tag+".srs", "binary", "")
		rule["rule_set"] = tag
	case "RULE-SET":
		provider, ok := s.providers[value]
		if !ok {
			return nil, "", fmt.Errorf("rule provider %q is missing", value)
		}
		providerMap := stringMap(provider)
		providerURL := textValue(providerMap["url"])
		if providerURL == "" {
			return nil, "", fmt.Errorf("rule provider %q has no URL", value)
		}
		format := strings.ToLower(textValue(providerMap["format"]))
		switch {
		case strings.HasSuffix(strings.ToLower(providerURL), ".srs"):
			format = "binary"
		case strings.HasSuffix(strings.ToLower(providerURL), ".json"):
			format = "source"
		case format == "srs":
			format = "binary"
		case format == "source":
		default:
			return nil, "", fmt.Errorf("rule provider %q must be a sing-box .srs or source .json provider", value)
		}
		interval := ""
		if seconds, ok := intValue(providerMap["interval"]); ok && seconds > 0 {
			interval = fmt.Sprintf("%ds", seconds)
		}
		tag := "provider-" + sanitizeTag(value)
		s.ensureRemoteRuleSet(tag, providerURL, format, interval)
		rule["rule_set"] = tag
	default:
		return nil, "", fmt.Errorf("unsupported Clash rule type %q", ruleType)
	}
	return rule, "", nil
}

func (s *conversionState) ensureRemoteRuleSet(tag, ruleURL, format, interval string) {
	if _, exists := s.ruleSetTags[tag]; exists {
		return
	}
	ruleSet := map[string]any{
		"type":   "remote",
		"tag":    tag,
		"format": format,
		"url":    ruleURL,
	}
	if interval != "" {
		ruleSet["update_interval"] = interval
	}
	s.ruleSets = append(s.ruleSets, ruleSet)
	s.ruleSetTags[tag] = struct{}{}
}

func convertDNS(root map[string]any) (map[string]any, string, bool) {
	dnsConfig := stringMap(root["dns"])
	nameservers, _ := stringList(dnsConfig["nameserver"])
	if len(nameservers) == 0 {
		nameservers = []string{"local"}
	}
	servers := make([]map[string]any, 0, len(nameservers)+1)
	resolverTag := ""
	for index, nameserver := range nameservers {
		server := dnsServer(nameserver, index)
		if server == nil {
			continue
		}
		if resolverTag == "" {
			resolverTag = textValue(server["tag"])
		}
		servers = append(servers, server)
	}
	if needsDNSBootstrap(servers) {
		bootstrap := map[string]any{"type": "local", "tag": "dns-bootstrap"}
		if defaultNameservers, ok := stringList(dnsConfig["default-nameserver"]); ok {
			for _, nameserver := range defaultNameservers {
				candidate := dnsServer(nameserver, 0)
				if candidate == nil || dnsServerNeedsBootstrap(candidate) {
					continue
				}
				candidate["tag"] = "dns-bootstrap"
				bootstrap = candidate
				break
			}
		}
		for _, server := range servers {
			if dnsServerNeedsBootstrap(server) {
				server["domain_resolver"] = "dns-bootstrap"
			}
		}
		servers = append([]map[string]any{bootstrap}, servers...)
	}
	if len(servers) == 0 {
		servers = append(servers, map[string]any{"type": "local", "tag": "dns-local"})
		resolverTag = "dns-local"
	}

	fakeIP := strings.EqualFold(textValue(dnsConfig["enhanced-mode"]), "fake-ip")
	dnsRules := make([]map[string]any, 0, 1)
	if fakeIP {
		inet4Range := textValue(dnsConfig["fake-ip-range"])
		if inet4Range == "" {
			inet4Range = "198.18.0.0/15"
		}
		fakeServer := map[string]any{
			"type":        "fakeip",
			"tag":         "dns-fakeip",
			"inet4_range": inet4Range,
		}
		if boolValue(root["ipv6"], false) {
			fakeServer["inet6_range"] = "fc00::/18"
		}
		servers = append(servers, fakeServer)
		dnsRules = append(dnsRules, map[string]any{
			"query_type": []string{"A", "AAAA"},
			"server":     "dns-fakeip",
		})
	}

	strategy := "ipv4_only"
	if boolValue(root["ipv6"], false) {
		strategy = "prefer_ipv4"
	}
	options := map[string]any{
		"servers":  servers,
		"final":    resolverTag,
		"strategy": strategy,
	}
	if len(dnsRules) != 0 {
		options["rules"] = dnsRules
	}
	return options, resolverTag, fakeIP
}

func dnsServer(raw string, index int) map[string]any {
	raw = strings.TrimSpace(raw)
	if hash := strings.IndexByte(raw, '#'); hash >= 0 {
		raw = raw[:hash]
	}
	tag := fmt.Sprintf("dns-%d", index+1)
	if raw == "" || strings.EqualFold(raw, "system") || strings.EqualFold(raw, "local") {
		return map[string]any{"type": "local", "tag": tag}
	}
	if !strings.Contains(raw, "://") {
		host, port := splitHostPortDefault(raw, 53)
		return map[string]any{"type": "udp", "tag": tag, "server": host, "server_port": port}
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return nil
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := parsed.Hostname()
	if scheme == "dhcp" {
		server := map[string]any{"type": "dhcp", "tag": tag}
		if host != "" {
			server["interface"] = host
		}
		return server
	}
	if host == "" {
		return nil
	}
	defaultPort := 53
	switch scheme {
	case "tls", "quic":
		defaultPort = 853
	case "https", "h3":
		defaultPort = 443
	case "udp", "tcp":
	default:
		return nil
	}
	port := defaultPort
	if parsed.Port() != "" {
		if parsedPort, parseErr := strconv.Atoi(parsed.Port()); parseErr == nil {
			port = parsedPort
		}
	}
	server := map[string]any{
		"type":        scheme,
		"tag":         tag,
		"server":      host,
		"server_port": port,
	}
	if (scheme == "https" || scheme == "h3") && parsed.EscapedPath() != "" && parsed.EscapedPath() != "/" {
		server["path"] = parsed.EscapedPath()
	}
	return server
}

func needsDNSBootstrap(servers []map[string]any) bool {
	for _, server := range servers {
		if dnsServerNeedsBootstrap(server) {
			return true
		}
	}
	return false
}

func dnsServerNeedsBootstrap(server map[string]any) bool {
	host := strings.Trim(textValue(server["server"]), "[]")
	return host != "" && net.ParseIP(host) == nil
}

func applyDialOptions(source, destination map[string]any) {
	if boolValue(source["tfo"], false) {
		destination["tcp_fast_open"] = true
	}
	if boolValue(source["mptcp"], false) {
		destination["tcp_multi_path"] = true
	}
	if detour := textValue(source["dialer-proxy"]); detour != "" {
		destination["detour"] = targetTag(detour)
	}
}

func applyTLS(source, destination map[string]any, force bool) error {
	reality := stringMap(source["reality-opts"])
	enabled := force || boolValue(source["tls"], false) || len(reality) != 0
	if !enabled {
		return nil
	}
	tls := map[string]any{"enabled": true}
	if serverName := firstText(source, "servername", "server-name", "sni"); serverName != "" {
		tls["server_name"] = serverName
	}
	if boolValue(source["skip-cert-verify"], false) {
		tls["insecure"] = true
	}
	if alpn, ok := stringList(source["alpn"]); ok && len(alpn) != 0 {
		tls["alpn"] = alpn
	}
	isXHTTP3 := false
	if strings.EqualFold(textValue(source["network"]), "xhttp") {
		if alpn, ok := stringList(source["alpn"]); ok && len(alpn) == 1 && alpn[0] == "h3" {
			isXHTTP3 = true
		}
	}
	if fingerprint := firstText(source, "client-fingerprint", "fingerprint"); fingerprint != "" && !isXHTTP3 {
		tls["utls"] = map[string]any{
			"enabled":     true,
			"fingerprint": fingerprint,
		}
	}
	if len(reality) != 0 {
		publicKey := firstText(reality, "public-key", "public_key")
		if publicKey == "" {
			return fmt.Errorf("Reality requires public-key")
		}
		realityOptions := map[string]any{
			"enabled":    true,
			"public_key": publicKey,
		}
		if shortID := firstText(reality, "short-id", "short_id"); shortID != "" {
			realityOptions["short_id"] = shortID
		}
		tls["reality"] = realityOptions
	}
	destination["tls"] = tls
	return nil
}

func applyTransport(source, destination map[string]any) error {
	network := strings.ToLower(textValue(source["network"]))
	switch network {
	case "", "tcp":
		return nil
	case "ws", "websocket":
		options := stringMap(source["ws-opts"])
		transport := map[string]any{"type": "ws"}
		copyText(options, transport, "path", "path")
		if headers := headerMap(options["headers"]); len(headers) != 0 {
			transport["headers"] = headers
		}
		copyInt(options, transport, "max-early-data", "max_early_data")
		copyText(options, transport, "early-data-header-name", "early_data_header_name")
		destination["transport"] = transport
	case "grpc":
		options := stringMap(source["grpc-opts"])
		transport := map[string]any{"type": "grpc"}
		if serviceName := firstText(options, "grpc-service-name", "service-name"); serviceName != "" {
			transport["service_name"] = serviceName
		}
		destination["transport"] = transport
	case "h2", "http":
		key := "h2-opts"
		if network == "http" {
			key = "http-opts"
		}
		options := stringMap(source[key])
		transport := map[string]any{"type": "http"}
		copyText(options, transport, "path", "path")
		if host, ok := stringList(options["host"]); ok && len(host) != 0 {
			transport["host"] = host
		}
		if headers := headerMap(options["headers"]); len(headers) != 0 {
			transport["headers"] = headers
		}
		destination["transport"] = transport
	case "httpupgrade", "http-upgrade":
		options := stringMap(source["http-upgrade-opts"])
		transport := map[string]any{"type": "httpupgrade"}
		copyText(options, transport, "path", "path")
		copyText(options, transport, "host", "host")
		if headers := headerMap(options["headers"]); len(headers) != 0 {
			transport["headers"] = headers
		}
		destination["transport"] = transport
	case "xhttp":
		options := stringMap(source["xhttp-opts"])
		mode := strings.ToLower(textValue(options["mode"]))
		switch mode {
		case "", "auto", "stream-one":
		default:
			return fmt.Errorf("XHTTP mode %q is not supported by this core build", mode)
		}
		transport := map[string]any{"type": "xhttp"}
		copyText(options, transport, "path", "path")
		copyText(options, transport, "host", "host")
		if mode != "" {
			transport["mode"] = mode
		}
		if headers := headerMap(options["headers"]); len(headers) != 0 {
			transport["headers"] = headers
		}
		if boolValue(options["no-grpc-header"], false) {
			transport["no_grpc_header"] = true
		}
		copyText(options, transport, "x-padding-bytes", "x_padding_bytes")
		reuse := stringMap(options["reuse-settings"])
		if len(reuse) != 0 {
			nativeReuse := map[string]any{}
			copyText(reuse, nativeReuse, "max-concurrency", "max_concurrency")
			copyText(reuse, nativeReuse, "max-connections", "max_connections")
			copyText(reuse, nativeReuse, "c-max-reuse-times", "c_max_reuse_times")
			copyText(reuse, nativeReuse, "h-max-request-times", "h_max_request_times")
			copyText(reuse, nativeReuse, "h-max-reusable-secs", "h_max_reusable_secs")
			copyText(reuse, nativeReuse, "h-keep-alive-period", "h_keep_alive_period")
			if len(nativeReuse) != 0 {
				transport["reuse_settings"] = nativeReuse
			}
			if keepAlive := secondsDuration(firstText(reuse, "h-keep-alive-period")); keepAlive != "" {
				transport["keep_alive_period"] = keepAlive
			}
		}
		destination["transport"] = transport
	default:
		return fmt.Errorf("transport %q is not supported by this core build", network)
	}
	return nil
}

func secondsDuration(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if strings.Contains(value, "-") {
		value = strings.TrimSpace(strings.SplitN(value, "-", 2)[0])
	}
	if _, err := strconv.Atoi(value); err != nil {
		return ""
	}
	return value + "s"
}

func copyBandwidth(source, destination map[string]any) {
	if value, ok := bandwidthValue(firstValue(source, "up", "up-mbps")); ok {
		destination["up_mbps"] = value
	}
	if value, ok := bandwidthValue(firstValue(source, "down", "down-mbps")); ok {
		destination["down_mbps"] = value
	}
}

func bandwidthValue(value any) (int, bool) {
	if number, ok := intValue(value); ok {
		return number, true
	}
	text := textValue(value)
	match := regexp.MustCompile(`\d+`).FindString(text)
	if match == "" {
		return 0, false
	}
	number, err := strconv.Atoi(match)
	return number, err == nil
}

func flattenPluginOptions(options map[string]any) string {
	keys := make([]string, 0, len(options))
	for key := range options {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		value := textValue(options[key])
		if value == "true" {
			parts = append(parts, key)
		} else {
			parts = append(parts, key+"="+value)
		}
	}
	return strings.Join(parts, ";")
}

func defaultFinalTag(groups, proxies []map[string]any) string {
	if len(groups) != 0 {
		return requiredText(groups[0], "name", "proxy group")
	}
	if len(proxies) != 0 {
		return requiredText(proxies[0], "name", "proxy")
	}
	return "direct"
}

func targetTag(target string) string {
	switch strings.ToUpper(strings.TrimSpace(target)) {
	case "DIRECT", "PASS":
		return "direct"
	case "REJECT", "REJECT-DROP", "BLOCK":
		return "block"
	default:
		return strings.TrimSpace(target)
	}
}

func splitRule(rule string) []string {
	rawParts := strings.Split(rule, ",")
	parts := make([]string, 0, len(rawParts))
	for _, part := range rawParts {
		part = strings.TrimSpace(part)
		if part == "" || strings.EqualFold(part, "no-resolve") {
			continue
		}
		parts = append(parts, part)
	}
	return parts
}

func applyPortRule(rule map[string]any, value, portKey, rangeKey string) error {
	if strings.Contains(value, "-") || strings.Contains(value, ":") {
		value = strings.ReplaceAll(value, "-", ":")
		rule[rangeKey] = value
		return nil
	}
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("invalid port %q", value)
	}
	rule[portKey] = port
	return nil
}

func wildcardRegex(pattern string) string {
	var builder strings.Builder
	builder.WriteString("^")
	for _, character := range pattern {
		switch character {
		case '*':
			builder.WriteString(".*")
		case '?':
			builder.WriteString(".")
		default:
			builder.WriteString(regexp.QuoteMeta(string(character)))
		}
	}
	builder.WriteString("$")
	return builder.String()
}

func sanitizeTag(value string) string {
	var builder strings.Builder
	for _, character := range strings.ToLower(value) {
		if (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '-' || character == '_' {
			builder.WriteRune(character)
		} else {
			builder.WriteRune('-')
		}
	}
	return strings.Trim(builder.String(), "-")
}

func ensurePrefix(address string) string {
	if strings.Contains(address, "/") {
		return address
	}
	parsed := net.ParseIP(address)
	if parsed != nil && parsed.To4() == nil {
		return address + "/128"
	}
	return address + "/32"
}

func splitHostPortDefault(value string, defaultPort int) (string, int) {
	if host, portText, err := net.SplitHostPort(value); err == nil {
		if port, parseErr := strconv.Atoi(portText); parseErr == nil {
			return host, port
		}
	}
	return strings.Trim(value, "[]"), defaultPort
}

func requiredServer(source map[string]any, name string) (string, error) {
	server := textValue(source["server"])
	if server == "" {
		return "", fmt.Errorf("proxy %q is missing server", name)
	}
	return server, nil
}

func requiredPort(source map[string]any, name string) (int, error) {
	port, ok := intValue(source["port"])
	if !ok || port < 1 || port > 65535 {
		return 0, fmt.Errorf("proxy %q has invalid port", name)
	}
	return port, nil
}

func requiredText(source map[string]any, key, context string) string {
	value := textValue(source[key])
	if value == "" {
		return ""
	}
	return value
}

func firstText(source map[string]any, keys ...string) string {
	for _, key := range keys {
		if value := textValue(source[key]); value != "" {
			return value
		}
	}
	return ""
}

func firstValue(source map[string]any, keys ...string) any {
	for _, key := range keys {
		if value, exists := source[key]; exists {
			return value
		}
	}
	return nil
}

func copyText(source, destination map[string]any, sourceKey, destinationKey string) {
	if value := textValue(source[sourceKey]); value != "" {
		destination[destinationKey] = value
	}
}

func copyInt(source, destination map[string]any, sourceKey, destinationKey string) {
	if value, ok := intValue(source[sourceKey]); ok {
		destination[destinationKey] = value
	}
}

func copyDuration(source, destination map[string]any, sourceKey, destinationKey, numericUnit string) {
	raw, exists := source[sourceKey]
	if !exists {
		return
	}
	if value, ok := intValue(raw); ok {
		destination[destinationKey] = strconv.Itoa(value) + numericUnit
		return
	}
	if value := textValue(raw); value != "" {
		destination[destinationKey] = value
	}
}

func copyStringList(source, destination map[string]any, sourceKey, destinationKey string) {
	if value, ok := stringList(source[sourceKey]); ok && len(value) != 0 {
		destination[destinationKey] = value
	}
}

func headerMap(value any) map[string]any {
	source := stringMap(value)
	if len(source) == 0 {
		return nil
	}
	result := make(map[string]any, len(source))
	for key, raw := range source {
		if values, ok := stringList(raw); ok && len(values) > 1 {
			result[key] = values
		} else {
			result[key] = textValue(raw)
		}
	}
	return result
}

func textValue(value any) string {
	switch typed := value.(type) {
	case nil:
		return ""
	case string:
		return strings.TrimSpace(typed)
	case fmt.Stringer:
		return strings.TrimSpace(typed.String())
	case bool:
		return strconv.FormatBool(typed)
	case int:
		return strconv.Itoa(typed)
	case int64:
		return strconv.FormatInt(typed, 10)
	case uint64:
		return strconv.FormatUint(typed, 10)
	case float64:
		return strconv.FormatFloat(typed, 'f', -1, 64)
	default:
		return strings.TrimSpace(fmt.Sprint(typed))
	}
}

func intValue(value any) (int, bool) {
	switch typed := value.(type) {
	case int:
		return typed, true
	case int8:
		return int(typed), true
	case int16:
		return int(typed), true
	case int32:
		return int(typed), true
	case int64:
		return int(typed), true
	case uint:
		return int(typed), true
	case uint8:
		return int(typed), true
	case uint16:
		return int(typed), true
	case uint32:
		return int(typed), true
	case uint64:
		return int(typed), true
	case float64:
		return int(typed), true
	case string:
		number, err := strconv.Atoi(strings.TrimSpace(typed))
		return number, err == nil
	default:
		return 0, false
	}
}

func boolValue(value any, fallback bool) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		parsed, err := strconv.ParseBool(strings.TrimSpace(typed))
		if err == nil {
			return parsed
		}
	case int:
		return typed != 0
	}
	return fallback
}

func intSlice(value any) ([]int, bool) {
	items, ok := value.([]any)
	if !ok {
		return nil, false
	}
	result := make([]int, 0, len(items))
	for _, item := range items {
		number, valid := intValue(item)
		if !valid {
			return nil, false
		}
		result = append(result, number)
	}
	return result, true
}

func stringList(value any) ([]string, bool) {
	switch typed := value.(type) {
	case []any:
		result := make([]string, 0, len(typed))
		for _, item := range typed {
			text := textValue(item)
			if text != "" {
				result = append(result, text)
			}
		}
		return result, true
	case []string:
		return typed, true
	case string:
		if strings.TrimSpace(typed) == "" {
			return nil, true
		}
		return []string{strings.TrimSpace(typed)}, true
	default:
		return nil, false
	}
}

func stringListStrict(value any, context string) ([]string, error) {
	if value == nil {
		return nil, nil
	}
	result, ok := stringList(value)
	if !ok {
		return nil, fmt.Errorf("%s must be a list", context)
	}
	return result, nil
}

func mapList(value any, context string) ([]map[string]any, error) {
	if value == nil {
		return nil, nil
	}
	items, ok := value.([]any)
	if !ok {
		return nil, fmt.Errorf("%s must be a list", context)
	}
	result := make([]map[string]any, 0, len(items))
	for index, item := range items {
		mapped := stringMap(item)
		if mapped == nil {
			return nil, fmt.Errorf("%s item %d must be a mapping", context, index+1)
		}
		result = append(result, mapped)
	}
	return result, nil
}

func stringMap(value any) map[string]any {
	switch typed := value.(type) {
	case map[string]any:
		return typed
	case map[any]any:
		result := make(map[string]any, len(typed))
		for key, item := range typed {
			result[textValue(key)] = normalizeYAML(item)
		}
		return result
	default:
		return nil
	}
}

func normalizeYAML(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		result := make(map[string]any, len(typed))
		for key, item := range typed {
			result[key] = normalizeYAML(item)
		}
		return result
	case map[any]any:
		result := make(map[string]any, len(typed))
		for key, item := range typed {
			result[textValue(key)] = normalizeYAML(item)
		}
		return result
	case []any:
		result := make([]any, len(typed))
		for index, item := range typed {
			result[index] = normalizeYAML(item)
		}
		return result
	default:
		return value
	}
}
