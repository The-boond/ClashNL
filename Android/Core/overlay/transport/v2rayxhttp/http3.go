//go:build with_quic

package v2rayxhttp

import (
	"context"
	stdTLS "crypto/tls"
	"net/http"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func newHTTP3Transport(
	_ context.Context,
	dialer N.Dialer,
	serverAddr M.Socksaddr,
	baseTLSConfig tls.Config,
	keepAlivePeriod time.Duration,
) (http.RoundTripper, error) {
	if keepAlivePeriod == 0 {
		keepAlivePeriod = 10 * time.Second
	}
	transport := &http3.Transport{
		TLSClientConfig: &stdTLS.Config{},
		QUICConfig: &quic.Config{
			KeepAlivePeriod:    keepAlivePeriod,
			MaxIdleTimeout:     300 * time.Second,
			MaxIncomingStreams: -1,
		},
		Dial: func(ctx context.Context, addr string, _ *stdTLS.Config, quicConfig *quic.Config) (*quic.Conn, error) {
			clientTLS := baseTLSConfig.Clone()
			clientTLS.SetNextProtos([]string{http3.NextProtoH3})
			stdConfig, err := clientTLS.STDConfig()
			if err != nil {
				return nil, err
			}
			udpConn, err := dialer.DialContext(ctx, N.NetworkUDP, serverAddr)
			if err != nil {
				return nil, err
			}
			packetConn := bufio.NewUnbindPacketConn(udpConn)
			quicConn, err := quic.DialEarly(ctx, packetConn, udpConn.RemoteAddr(), stdConfig, quicConfig)
			if err != nil {
				_ = packetConn.Close()
				return nil, err
			}
			go func() {
				<-quicConn.Context().Done()
				_ = packetConn.Close()
			}()
			return quicConn, nil
		},
	}
	return transport, nil
}
