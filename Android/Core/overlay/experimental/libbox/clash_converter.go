package libbox

import (
	"github.com/sagernet/sing-box/experimental/libbox/internal/clashconv"
)

// ConvertClashConfig converts a Clash/Mihomo YAML profile to the native
// sing-box JSON profile used by ClashNl clients.
func ConvertClashConfig(configContent string) (*StringBox, error) {
	converted, err := clashconv.Convert(configContent)
	if err != nil {
		return nil, err
	}
	return wrapString(converted), nil
}
