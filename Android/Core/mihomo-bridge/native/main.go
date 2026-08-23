package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
#include "bridge.h"
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

var tunLock sync.Mutex
var activeTun *remoteTun
var coreLock sync.Mutex
var controllerInitialized bool
var controllerAddress string
var controllerSecret string

type remoteTun struct {
	closer   io.Closer
	callback unsafe.Pointer
	mutex    sync.RWMutex
	closed   bool
}

func main() {}

//export coreInit
func coreInit(home *C.char, sdkVersion C.int) {
	MC.SetHomeDir(C.GoString(home))
	_ = sdkVersion
	dialer.DefaultSocketHook = func(_ string, _ string, conn syscall.RawConn) error {
		var protectErr error
		controlErr := conn.Control(func(fd uintptr) {
			if current := currentTun(); current != nil && !current.protectSocket(int(fd)) {
				protectErr = errors.New("android: unable to protect Mihomo socket from VPN")
			}
		})
		return errors.Join(controlErr, protectErr)
	}
	process.DefaultPackageNameResolver = func(metadata *MC.Metadata) (string, error) {
		if metadata.RawSrcAddr == nil || metadata.RawDstAddr == nil {
			return "", process.ErrInvalidNetwork
		}
		current := currentTun()
		if current == nil {
			return "", process.ErrNotFound
		}
		uid := current.querySocketUid(metadata.RawSrcAddr.Network(), metadata.RawSrcAddr.String(), metadata.RawDstAddr.String())
		if uid < 0 {
			return "", process.ErrNotFound
		}
		metadata.Uid = uint32(uid)
		// UID rules work with the value above. Package-name lookup stays in the
		// Android layer, where PackageManager access is available.
		return fmt.Sprintf("uid:%d", uid), nil
	}
}

func currentTun() *remoteTun {
	tunLock.Lock()
	defer tunLock.Unlock()
	return activeTun
}

func decodeProfile(content string) (*config.RawConfig, error) {
	raw, err := config.UnmarshalRawConfig([]byte(content))
	if err != nil {
		return nil, err
	}
	if len(raw.Proxy) == 0 && len(raw.ProxyProvider) == 0 {
		return nil, errors.New("profile does not contain proxies or proxy-providers")
	}
	knownNames := make(map[string]struct{}, len(raw.Proxy)+len(raw.ProxyGroup))
	for index, mapping := range raw.Proxy {
		name, _ := mapping["name"].(string)
		proxyType, _ := mapping["type"].(string)
		if name == "" || proxyType == "" {
			return nil, fmt.Errorf("proxy %d must contain non-empty name and type", index)
		}
		if _, exists := knownNames[name]; exists {
			return nil, fmt.Errorf("duplicate proxy or group name: %s", name)
		}
		knownNames[name] = struct{}{}
	}
	for index, mapping := range raw.ProxyGroup {
		name, _ := mapping["name"].(string)
		groupType, _ := mapping["type"].(string)
		if name == "" || groupType == "" {
			return nil, fmt.Errorf("proxy group %d must contain non-empty name and type", index)
		}
		if _, exists := knownNames[name]; exists {
			return nil, fmt.Errorf("duplicate proxy or group name: %s", name)
		}
		knownNames[name] = struct{}{}
	}
	return raw, nil
}

func parseConfig(content, controller, secret string, httpProxyPort int) (*config.Config, error) {
	raw, err := decodeProfile(content)
	if err != nil {
		return nil, err
	}
	raw.ExternalController = controller
	raw.ExternalControllerTLS = ""
	raw.ExternalControllerUnix = ""
	raw.ExternalControllerPipe = ""
	raw.Secret = secret
	// The Android app owns the only inbound (VpnService TUN). Subscription
	// content must not be able to expose proxy, DNS, or server listeners.
	if httpProxyPort < 0 || httpProxyPort > 65535 {
		return nil, fmt.Errorf("invalid app-owned HTTP proxy port: %d", httpProxyPort)
	}
	raw.Port = httpProxyPort
	raw.SocksPort = 0
	raw.RedirPort = 0
	raw.TProxyPort = 0
	raw.MixedPort = 0
	raw.ShadowSocksConfig = ""
	raw.VmessConfig = ""
	raw.AllowLan = false
	raw.BindAddress = "127.0.0.1"
	raw.Authentication = nil
	raw.SkipAuthPrefixes = nil
	raw.Listeners = nil
	raw.Tunnels = nil
	raw.TuicServer = config.RawTuicServer{}
	raw.IPTables = config.RawIPTables{}
	raw.DNS.Listen = ""
	raw.NTP.WriteToSystem = false
	raw.ExternalUI = ""
	raw.ExternalUIURL = ""
	raw.ExternalUIName = ""
	raw.ExternalDohServer = ""
	raw.ExternalControllerCors = config.RawCors{}
	raw.Tun.Enable = false
	raw.Tun.AutoRoute = false
	raw.Tun.AutoDetectInterface = false
	// Selections are persisted per app profile by Kotlin. Mihomo's global cache
	// is shared by every subscription and would leak choices between equal group
	// names such as "PROXY".
	raw.Profile.StoreSelected = false
	raw.Profile.StoreFakeIP = true
	return config.ParseRawConfig(raw)
}

func errorString(err error) *C.char {
	if err == nil {
		return nil
	}
	return C.CString(err.Error())
}

//export validateConfig
func validateConfig(content, controller, secret *C.char) *C.char {
	// Import and edit validation must be deterministic and offline. Full Mihomo
	// parsing can initialize geodata and other remote-backed resources; that
	// happens once when the core is actually loaded instead of freezing the UI.
	_, err := decodeProfile(C.GoString(content))
	_ = controller
	_ = secret
	return errorString(err)
}

type offlineProxyGroup struct {
	Name     string   `json:"name"`
	Type     string   `json:"type"`
	Selected string   `json:"selected"`
	Proxies  []string `json:"proxies"`
}

type offlineProxyGroupResponse struct {
	Error  string              `json:"error,omitempty"`
	Groups []offlineProxyGroup `json:"groups"`
}

//export describeProxyGroups
func describeProxyGroups(content *C.char) *C.char {
	raw, err := config.UnmarshalRawConfig([]byte(C.GoString(content)))
	response := offlineProxyGroupResponse{Groups: make([]offlineProxyGroup, 0)}
	if err != nil {
		response.Error = err.Error()
	} else {
		for _, mapping := range raw.ProxyGroup {
			name, _ := mapping["name"].(string)
			groupType, _ := mapping["type"].(string)
			proxies := stringValues(mapping["proxies"])
			if name == "" || len(proxies) == 0 {
				continue
			}
			response.Groups = append(response.Groups, offlineProxyGroup{
				Name:     name,
				Type:     groupType,
				Selected: proxies[0],
				Proxies:  proxies,
			})
		}
	}
	encoded, marshalErr := json.Marshal(response)
	if marshalErr != nil {
		return C.CString(`{"error":"Unable to encode Mihomo proxy groups","groups":[]}`)
	}
	return C.CString(string(encoded))
}

func stringValues(value any) []string {
	values, ok := value.([]any)
	if !ok {
		return nil
	}
	result := make([]string, 0, len(values))
	for _, value := range values {
		if stringValue, ok := value.(string); ok && stringValue != "" {
			result = append(result, stringValue)
		}
	}
	return result
}

//export loadConfig
func loadConfig(content, controller, secret *C.char, httpProxyPort C.int) *C.char {
	coreLock.Lock()
	defer coreLock.Unlock()
	requestedAddress := C.GoString(controller)
	requestedSecret := C.GoString(secret)
	if controllerInitialized && (requestedAddress != controllerAddress || requestedSecret != controllerSecret) {
		return errorString(errors.New("Mihomo controller endpoint cannot change before process restart"))
	}
	cfg, err := parseConfig(C.GoString(content), requestedAddress, requestedSecret, int(httpProxyPort))
	if err == nil {
		if controllerInitialized {
			// Keep the authenticated loopback controller alive for this process.
			// Applying only the runtime config makes reload and restart synchronous
			// and avoids Mihomo's asynchronous controller replacement race.
			executor.ApplyConfig(cfg, true)
		} else {
			hub.ApplyConfig(cfg)
			controllerAddress = requestedAddress
			controllerSecret = requestedSecret
			controllerInitialized = true
		}
	}
	return errorString(err)
}

//export setMode
func setMode(mode *C.char) *C.char {
	value := strings.ToLower(strings.TrimSpace(C.GoString(mode)))
	parsed, exists := tunnel.ModeMapping[value]
	if !exists {
		return errorString(fmt.Errorf("unsupported Mihomo mode: %s", value))
	}
	tunnel.SetMode(parsed)
	return nil
}

//export prepareTun
func prepareTun(callback unsafe.Pointer) {
	tunLock.Lock()
	defer tunLock.Unlock()
	if activeTun != nil {
		activeTun.close()
	}
	activeTun = &remoteTun{callback: callback}
}

// The fd is owned by this function on entry. A successful listener closes it;
// every failure path closes it before returning.
//
//export startTun
func startTun(fd C.int, stack, gateway, dns *C.char) *C.char {
	tunLock.Lock()
	defer tunLock.Unlock()
	if activeTun == nil {
		closeOwnedFD(int(fd))
		return errorString(errors.New("android: TUN callback was not prepared"))
	}
	if activeTun.closer != nil {
		closeOwnedFD(int(fd))
		return errorString(errors.New("android: TUN is already active"))
	}

	options, err := makeTunOptions(int(fd), C.GoString(stack), C.GoString(gateway), C.GoString(dns))
	if err != nil {
		closeOwnedFD(int(fd))
		return errorString(err)
	}
	listener, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		// sing-tun may already have created and closed its native TUN before
		// returning an error. Closing the raw integer again can hit a reused fd,
		// so ownership remains with sing-tun once New has been entered.
		return errorString(err)
	}
	activeTun.closer = listener
	return nil
}

func closeOwnedFD(fd int) {
	if file := os.NewFile(uintptr(fd), "android-tun"); file != nil {
		_ = file.Close()
	}
}

func makeTunOptions(fd int, stack, gateway, dns string) (LC.Tun, error) {
	tunStack, ok := MC.StackTypeMapping[strings.ToLower(stack)]
	if !ok {
		tunStack = MC.TunSystem
	}
	var prefix4 []netip.Prefix
	var prefix6 []netip.Prefix
	for _, value := range strings.Split(gateway, ",") {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(value)
		if err != nil {
			return LC.Tun{}, fmt.Errorf("invalid VPN gateway %q: %w", value, err)
		}
		if prefix.Addr().Is4() {
			prefix4 = append(prefix4, prefix)
		} else {
			prefix6 = append(prefix6, prefix)
		}
	}
	var dnsHijack []string
	for _, value := range strings.Split(dns, ",") {
		value = strings.TrimSpace(value)
		if value != "" {
			dnsHijack = append(dnsHijack, net.JoinHostPort(value, "53"))
		}
	}
	return LC.Tun{
		Enable:              true,
		Device:              sing_tun.InterfaceName,
		Stack:               tunStack,
		DNSHijack:           dnsHijack,
		AutoRoute:           false,
		AutoDetectInterface: false,
		Inet4Address:        prefix4,
		Inet6Address:        prefix6,
		MTU:                 9000,
		FileDescriptor:      fd,
	}, nil
}

func (tun *remoteTun) close() {
	tun.mutex.Lock()
	defer tun.mutex.Unlock()
	if tun.closed {
		return
	}
	tun.closed = true
	if tun.closer != nil {
		_ = tun.closer.Close()
	}
	C.release_callback(tun.callback)
}

func (tun *remoteTun) protectSocket(fd int) bool {
	tun.mutex.RLock()
	defer tun.mutex.RUnlock()
	if tun.closed {
		return false
	}
	return C.protect_socket(tun.callback, C.int(fd)) != 0
}

func (tun *remoteTun) querySocketUid(network, source, target string) int {
	var protocol C.int
	switch strings.ToLower(network) {
	case "tcp", "tcp4", "tcp6":
		protocol = C.int(syscall.IPPROTO_TCP)
	case "udp", "udp4", "udp6":
		protocol = C.int(syscall.IPPROTO_UDP)
	default:
		return -1
	}
	tun.mutex.RLock()
	defer tun.mutex.RUnlock()
	if tun.closed {
		return -1
	}
	sourceValue := C.CString(source)
	targetValue := C.CString(target)
	defer C.free(unsafe.Pointer(sourceValue))
	defer C.free(unsafe.Pointer(targetValue))
	return int(C.query_socket_uid(tun.callback, protocol, sourceValue, targetValue))
}

//export stopTun
func stopTun() {
	tunLock.Lock()
	defer tunLock.Unlock()
	if activeTun != nil {
		activeTun.close()
		activeTun = nil
	}
}

//export stopCore
func stopCore() {
	coreLock.Lock()
	defer coreLock.Unlock()
	stopTun()
	if cfg, err := config.Parse([]byte{}); err == nil {
		// The controller remains bound to its random, authenticated loopback
		// endpoint until process exit. Keeping it stable lets future starts use
		// executor.ApplyConfig synchronously without replacing the HTTP server.
		executor.ApplyConfig(cfg, true)
	}
	runtime.GC()
}

func init() {
	log.SetLevel(log.INFO)
}
