package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
#include "bridge.h"
*/
import "C"

import (
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/hub"
)

var tunLock sync.Mutex
var activeTun *remoteTun

type remoteTun struct {
	closer io.Closer
	callback unsafe.Pointer
	mutex sync.RWMutex
	closed bool
}

func main() {}

//export coreInit
func coreInit(home *C.char, sdkVersion C.int) {
	MC.SetHomeDir(C.GoString(home))
	_ = sdkVersion
	dialer.DefaultSocketHook = func(_ string, _ string, conn syscall.RawConn) error {
		return conn.Control(func(fd uintptr) {
			if current := currentTun(); current != nil {
				current.protectSocket(int(fd))
			}
		})
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

func parseConfig(content, controller, secret string) (*config.Config, error) {
	raw, err := config.UnmarshalRawConfig([]byte(content))
	if err != nil {
		return nil, err
	}
	if len(raw.Proxy) == 0 && len(raw.ProxyProvider) == 0 {
		return nil, errors.New("profile does not contain proxies or proxy-providers")
	}
	raw.ExternalController = controller
	raw.ExternalControllerTLS = ""
	raw.ExternalControllerUnix = ""
	raw.ExternalControllerPipe = ""
	raw.Secret = secret
	raw.Tun.Enable = false
	raw.Tun.AutoRoute = false
	raw.Tun.AutoDetectInterface = false
	raw.Profile.StoreSelected = true
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
	_, err := parseConfig(C.GoString(content), C.GoString(controller), C.GoString(secret))
	return errorString(err)
}

//export loadConfig
func loadConfig(content, controller, secret *C.char) *C.char {
	cfg, err := parseConfig(C.GoString(content), C.GoString(controller), C.GoString(secret))
	if err == nil {
		hub.ApplyConfig(cfg)
	}
	return errorString(err)
}

//export startTun
func startTun(fd C.int, stack, gateway, dns *C.char, callback unsafe.Pointer) *C.char {
	tunLock.Lock()
	defer tunLock.Unlock()
	if activeTun != nil {
		activeTun.close()
		activeTun = nil
	}

	options, err := makeTunOptions(int(fd), C.GoString(stack), C.GoString(gateway), C.GoString(dns))
	if err != nil {
		C.release_callback(callback)
		return errorString(err)
	}
	listener, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		C.release_callback(callback)
		return errorString(err)
	}
	activeTun = &remoteTun{closer: listener, callback: callback}
	return nil
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
		Enable: true,
		Device: sing_tun.InterfaceName,
		Stack: tunStack,
		DNSHijack: dnsHijack,
		AutoRoute: false,
		AutoDetectInterface: false,
		Inet4Address: prefix4,
		Inet6Address: prefix6,
		MTU: 9000,
		FileDescriptor: fd,
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
	stopTun()
	if cfg, err := config.Parse([]byte{}); err == nil {
		hub.ApplyConfig(cfg)
	}
	runtime.GC()
}

func init() {
	log.SetLevel(log.INFO)
}
