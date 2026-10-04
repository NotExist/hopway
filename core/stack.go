package core

import (
	"fmt"

	"github.com/xjasonlyu/tun2socks/v2/core/option"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// createStack 建立接在 TUN 上的 gVisor stack。
//
// 不用 tun2socks 的 core.CreateStack:它會在套用自訂 option 之後才裝自己的 TCP/UDP handler,
// 無法換成我們「先連遠端再握手」的 forwarder;而 handler 必須在建立 NIC 之前裝好,
// 否則會與 NIC 的收包 goroutine 發生 data race。
// 也刻意不裝 tun2socks 對任意 IP 偽造 ping 回覆的 ICMP handler:SSH 無法轉送 ICMP,假回覆只會誤導。
func createStack(ep stack.LinkEndpoint, h *handler) (*stack.Stack, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	for _, opt := range []option.Option{
		option.WithDefault(),
		// SSH 本身已有可靠傳輸,netstack 端放大 buffer 換吞吐
		option.WithTCPReceiveBufferSize(4 << 20),
		option.WithTCPSendBufferSize(4 << 20),
	} {
		if err := opt(s); err != nil {
			s.Close()
			return nil, err
		}
	}
	h.installForwarders(s)

	nicID := s.NextNICID()
	if err := s.CreateNICWithOptions(nicID, ep, stack.NICOptions{}); err != nil {
		s.Close()
		return nil, fmt.Errorf("create NIC: %s", err)
	}
	// promiscuous:接收送往任意位址的封包;spoofing:以任意來源位址回應
	if err := s.SetPromiscuousMode(nicID, true); err != nil {
		s.Close()
		return nil, fmt.Errorf("set promiscuous mode: %s", err)
	}
	if err := s.SetSpoofing(nicID, true); err != nil {
		s.Close()
		return nil, fmt.Errorf("set spoofing: %s", err)
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	return s, nil
}
