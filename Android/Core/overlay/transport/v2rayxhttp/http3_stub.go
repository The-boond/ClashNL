//go:build !with_quic

package v2rayxhttp

import (
	"context"
	"net/http"
	"time"

	"github.com/sagernet/sing-box/common/tls"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func newHTTP3Transport(
	context.Context,
	N.Dialer,
	M.Socksaddr,
	tls.Config,
	time.Duration,
) (http.RoundTripper, error) {
	return nil, E.New("XHTTP3 support is not included in this build")
}
