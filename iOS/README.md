# ClashNl

ClashNl Apple 是面向 iOS、macOS 与 tvOS 的开源网络代理客户端，使用 Apple
`NEPacketTunnelProvider` 建立设备级数据通道，并在导入时将常用
Clash/Mihomo YAML 严格转换为原生 sing-box JSON。

> 当前阶段：Apple 客户端 MVP。核心配置转换器及现代协议示例已通过自动化测试和
> sing-box 1.14 配置检查；平台签名、真机回归及商店审核材料需在发布账号下完成。

## ClashNL 第一批 iOS 体验改造

- 新建配置菜单提供独立的 **Add Subscription URL** 入口，打开后直接进入远程订阅表单；
- 远程订阅 URL 在保存前统一做 HTTP/HTTPS、主机和嵌入式凭据校验；
- 配置列表和仪表盘显示订阅已用/总流量、到期时间和最近更新时间；
- App 设置页保留系统默认、English、简体中文、繁体中文、Русский、فارسی 选择，
  目标包的 `Info.plist` 缺少本地化列表时仍使用内置回退列表。

## MVP 能力

- 本地文件、远程订阅、二维码和设备传输导入
- Clash/Mihomo YAML 与 sing-box JSON
- `select`、`url-test`，以及基于 URLTest 的 `fallback` 兼容
- 域名、CIDR、端口、进程、GeoIP、GeoSite 和 sing-box 规则集
- FakeIP DNS、TUN、规则路由、延迟测试、Clash API
- 首次启动隐私披露；订阅内容和凭据留在设备/App Group
- iCloud 配置同步（用户开启时）

### 协议

| 类别 | 当前转换支持 |
| --- | --- |
| 主流 | Shadowsocks、VMess、VLESS、Trojan、HTTP(S)、SOCKS5 |
| 现代 | VLESS Reality、Hysteria 1/2、TUIC v5、AnyTLS、ShadowTLS |
| 其他 | Snell、Naive、SSH、WireGuard endpoint |
| 传输 | WebSocket、gRPC、HTTP/2、HTTP Upgrade、TLS、uTLS |

转换器采取“明确报错”策略。`load-balance`、`relay`、传统 Clash
behavior 规则集等没有可靠等价语义的项目不会被静默丢弃。
`fallback` 会按 sing-box URLTest 的健康检查/延迟选择行为运行，不等同于
Mihomo 的严格顺序回退。

## 项目结构

```text
Core/clashconv/                         Clash YAML -> sing-box JSON
Core/overlay/experimental/libbox/       gomobile 暴露给 Swift 的 API
Examples/clash-modern.yaml              Reality/Hy2/TUIC/AnyTLS 示例
Library/Shared/ProfileContentNormalizer.swift
scripts/build-libbox.sh                 构建固定版本 Libbox.xcframework
scripts/configure-apple-signing.sh      写入 Apple Team ID
SFI/                                    iOS 主应用
Extension/                              Packet Tunnel Provider
sing-box.xcodeproj/                     Apple 平台 Xcode 工程
```

## 本地验证转换器

转换器需要 Go 1.24 或更高版本；完整 Libbox 构建按固定内核要求使用
Go 1.24.7 或更高版本：

```bash
cd Core/clashconv
go test ./...
go run ./cmd/clashnl-convert ../../Examples/clash-modern.yaml > /tmp/clashnl.json
```

完整校验（包含固定版本 sing-box 的 Libbox 包编译与 `check`）：

```bash
bash scripts/verify-core.sh
```

## 构建 iOS

需要 macOS、当前稳定版 Xcode、Go，以及加入组织的 Apple Developer 账号。

```bash
git submodule update --init --recursive
bash scripts/build-libbox.sh
bash scripts/configure-apple-signing.sh YOUR_TEAM_ID com.yourcompany.clashnl
open sing-box.xcodeproj
```

随后在 Xcode 中：

1. 为脚本中使用的 base bundle ID 及其扩展注册 App ID。
2. 注册对应的 `group.<base bundle ID>` App Group 和
   `iCloud.<base bundle ID>` iCloud container。
3. 为主应用和扩展开启 Network Extensions、App Groups、iCloud、
   File Provider 等项目中已有的 capabilities。
4. 选择 `SFI` scheme（产品名为 ClashNl）和真机，执行 Build。
5. 首次启动接受披露，导入 `Examples/clash-modern.yaml`，启动隧道并检查
   DNS、IPv4/IPv6、UDP、切网、锁屏恢复和订阅更新。

如先做纯 UI 模拟器检查，可使用：

```bash
xcodebuild \
  -project sing-box.xcodeproj \
  -scheme SFI \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## 发布清单

- App Store Connect 中选择免费或最低可用价格档（例如 US$0.99）。
- 在购买/使用前展示 VPN 数据实践；App Privacy 与
  `PrivacyInfo.xcprivacy` 保持一致。
- 确认 [PRIVACY.md](PRIVACY.md) 中的公开支持邮箱有效，将其发布为可访问的
  隐私政策 URL，并填写到 App Store Connect。
- Review Notes 说明隧道仅由用户主动开启、配置从哪里来，并提供专用、可用且
  可轮换的审核测试订阅或二维码（凭据不要提交到仓库）。
- 提供可复现的完整对应源代码、构建脚本、GPL 许可证与修改说明。
- 按销售地区确认 VPN 应用所需许可和上架范围。
- TestFlight 先覆盖断网、Wi‑Fi/蜂窝切换、休眠、低内存与高并发场景。

## 内核升级

`scripts/build-libbox.sh` 固定 `CORE_REF`，避免“今天能编译、明天行为改变”。
升级协议时先修改提交号，再依次执行转换器测试、sing-box `check`、iOS
模拟器构建和真机回归，最后才发布。

## 来源与许可证

本项目基于
[SagerNet/sing-box-for-apple](https://github.com/SagerNet/sing-box-for-apple)
并链接 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)。
代码按 [GPL-3.0-or-later](LICENSE) 分发；详细归属见 [NOTICE](NOTICE)。
固定内核的附加名称/关联说明保存在
[LICENSES/sing-box.txt](LICENSES/sing-box.txt)；本项目使用独立品牌且不暗示
与上游存在关联。
