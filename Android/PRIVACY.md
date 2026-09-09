# ClashNl Android Privacy Policy / Android 隐私政策

Last updated / 最后更新：2026-07-28

> Release owner: replace `PRIVACY_CONTACT_EMAIL` with the public support
> address before publishing this policy.
>
> 发布负责人：公开此政策前，将 `PRIVACY_CONTACT_EMAIL` 替换为公开支持邮箱。

## 中文

### 我们收集的数据

ClashNl 开发者不收集、出售、使用或向第三方披露用户的浏览记录、DNS 查询、
网络流量内容、IP 地址、设备标识符、分析数据或代理配置凭据。ClashNl 不包含
广告或追踪 SDK。

### 设备本地数据

用户导入的配置、节点选择、运行日志和崩溃报告保存在 Android 应用私有目录中。
用户可以在应用内删除配置和日志，也可以通过删除应用清除应用数据。

### Android VPN 与权限

Android 版本使用 `VpnService` 创建本地 TUN 接口，并在首次使用时显示流量
用途说明、要求用户主动确认。只有配置包含 Wi‑Fi SSID/BSSID 路由条件时才请求
位置权限，该信息仅在设备上用于规则匹配；相机权限仅用于用户主动打开的二维码
扫描；通知权限用于显示前台 VPN 状态。ClashNl 开发者不接收这些权限产生的数据。

### 网络请求

订阅更新会请求用户提供的订阅地址。用户启用更新检查时，应用可能访问 Apple
Google Play、GitHub 或 F-Droid 的更新服务。VPN 开启后，流量会
依据用户选择的配置发送到配置中指定的 DNS 和代理服务器。上述服务器由用户或
其服务提供方控制，并受相应服务提供方的隐私政策约束。

仪表盘的公网 IP 卡片仅在用户点击刷新时请求 Cloudflare 的
`/cdn-cgi/trace` 服务，并在本机显示返回的公网 IP、地区和接入点。ClashNl
开发者不接收或保存该结果；Cloudflare 会按其隐私政策处理该网络请求。

### 联系

隐私问题请通过项目 GitHub Issues 联系维护者。请勿公开个人信息、订阅链接或日志原文。

## English

### Data we collect

The ClashNl developer does not collect, sell, use, or disclose users' browsing
history, DNS queries, network traffic content, IP addresses, device
identifiers, analytics, or proxy profile credentials. ClashNl contains no
advertising or tracking SDK.

### On-device data

Imported profiles, outbound selections, runtime logs, and crash reports remain
in the Android app's private storage. Users can delete profiles and logs inside
the app or remove all app data by deleting the app.

### Android VPN and permissions

The Android app uses `VpnService` to create a local TUN interface and presents
a prominent traffic-use disclosure that requires affirmative action on first
use. Location permission is requested only when a profile contains Wi-Fi
SSID/BSSID routing conditions and is used on device for rule matching. Camera
permission is used only when the user opens QR scanning. Notification
permission is used for foreground VPN status. The ClashNl developer does not
receive data produced by these permissions.

### Network requests

Subscription updates contact URLs supplied by the user. If the user enables
update checks, the app may contact Google Play, GitHub, or F-Droid update
services. When the VPN is enabled, traffic is sent according to
the selected profile to DNS and proxy servers specified in that profile. Those
servers are controlled by the user or the user's service provider and are
governed by that provider's privacy policy.

The dashboard public-IP card contacts Cloudflare's `/cdn-cgi/trace` service
only after the user taps refresh, then displays the returned public IP, region,
and point of presence on device. The ClashNl developer does not receive or
store that result; Cloudflare processes the request under its privacy policy.

### Contact

For privacy questions, contact the maintainers through GitHub Issues. Do not post personal information, subscription URLs, or raw logs.
