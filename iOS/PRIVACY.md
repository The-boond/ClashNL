# ClashNl Apple Privacy Policy / Apple 隐私政策

Last updated / 最后更新：2026-09-04

> Public support address: `deng2504607315@gmail.com`.
>
> 公开支持邮箱：`deng2504607315@gmail.com`。

## 中文

### 我们收集的数据

ClashNl 开发者不收集、出售、使用或向第三方披露用户的浏览记录、DNS 查询、
网络流量内容、IP 地址、设备标识符、分析数据或代理配置凭据。ClashNl 不包含
广告或追踪 SDK。

### 设备本地数据

用户导入的配置、节点选择、运行日志和崩溃报告保存在应用容器或 App Group
容器中。用户可以在应用内删除配置和日志，也可以通过删除应用清除应用数据。

### iCloud

只有用户主动选择 iCloud 配置功能时，相应配置文件才会通过用户自己的 iCloud
账户和 ClashNl iCloud 容器同步。ClashNl 开发者不接收这些文件。

### 网络请求

订阅更新会请求用户提供的订阅地址。用户启用更新检查时，应用可能访问 Apple
App Store 或 GitHub 的更新服务。VPN 开启后，流量会
依据用户选择的配置发送到配置中指定的 DNS 和代理服务器。上述服务器由用户或
其服务提供方控制，并受相应服务提供方的隐私政策约束。

用户在“网络路径”或 STUN 工具中主动刷新时，应用会向用户配置的 STUN 服务器
（未自定义时使用应用内置的默认服务器）发送 UDP 探测。STUN 服务器会看到该次
探测的来源公网 IP，并向应用返回经 NAT 映射后的公网 IP 和端口。该结果仅在设备
上展示；ClashNl 开发者不会接收或保存结果。STUN 只反映 UDP/NAT 路径，不代表
HTTP、TCP 或所有分流规则的出口；UDP 不可用导致探测失败也不表示 VPN 已损坏。

在远程控制模式下，UDP 探测由所连接的远程服务发出，结果通过远程控制通道返回
本设备展示；因此结果反映远程服务的出口，而不是本设备的出口。

### 联系

隐私问题请联系：`deng2504607315@gmail.com`

## English

### Data we collect

The ClashNl developer does not collect, sell, use, or disclose users' browsing
history, DNS queries, network traffic content, IP addresses, device
identifiers, analytics, or proxy profile credentials. ClashNl contains no
advertising or tracking SDK.

### On-device data

Imported profiles, outbound selections, runtime logs, and crash reports remain
in the app container. Shared Apple-platform features may use an App Group
container. Users can delete profiles and logs inside the app or remove all app
data by deleting the app.

### iCloud

Profile files sync through the user's own iCloud account and the ClashNl iCloud
container only when the user explicitly chooses the iCloud profile feature.
The ClashNl developer does not receive these files.

### Network requests

Subscription updates contact URLs supplied by the user. If the user enables
update checks, the app may contact Apple App Store or GitHub update services.
When the VPN is enabled, traffic is sent according to
the selected profile to DNS and proxy servers specified in that profile. Those
servers are controlled by the user or the user's service provider and are
governed by that provider's privacy policy.

When the user manually refreshes Network Paths or runs the STUN tool, the app
sends UDP probes to the user-configured STUN server (or the app's built-in
default when none is configured). That server observes the probe's source
public IP address and returns the NAT-mapped public IP address and port. The
result is displayed only on the device; the ClashNl developer does not receive
or retain it. STUN describes only a UDP/NAT path, not the HTTP, TCP, or every
routing-rule egress. A failed probe may mean UDP is unavailable and does not by
itself mean that the VPN is broken.

In remote-control mode, the connected remote service sends the UDP probes and
returns the result through the remote-control channel for display on this
device. The result therefore describes the remote service's egress, not this
device's egress.

### Contact

For privacy questions, contact: `deng2504607315@gmail.com`
