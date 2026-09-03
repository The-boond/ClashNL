# ClashNL iOS 0.1.0 Preview 2

> **源码预览，尚未经过 Xcode 编译、模拟器、签名或 iPhone 真机验证，不提供 IPA。**
>
> **由于环境限制，不一定能用，期待大佬进行实机测试。**

本预览继续以 iOS 原生的 SwiftUI、Network Extension 和系统生命周期为基础，参考 Android 已验证的诊断能力，但没有照搬 Android 的网络接口或界面实现。

## 主要变化

- Packet Tunnel 扩展记录真实物理上游类型、接口名称和本地接口地址，并通过 App Group 向主应用提供带时效的快照。
- 仪表盘新增网络诊断卡，VPN 连接时分别检测 `direct` 物理出口与默认/final VPN 出口。
- 连接状态下若扩展快照缺失，不会把 `utun` 或主应用看到的 VPN 路径误标为物理入口。
- 保留 sing-box 原有接口选择逻辑；诊断用的物理接口选择不会改变内核实际路由。
- 网络、服务或节点状态变化后会清除陈旧结果；STUN 检测由用户主动刷新，并显示独立错误和完成时间。
- 远程仪表盘明确显示结果属于远程目标，不伪装成本机物理入口。
- 已有用户升级后会看到新的网络诊断卡，同时尊重用户后续手动关闭卡片的选择。
- 转换器测试确认保留 `direct` 出站，并启用 `auto_detect_interface`。

## 尚未完成的验证

当前开发环境为 Windows，没有 macOS、Xcode、Apple 签名环境或可连接的 iPhone，因此以下项目仍需实机开发者验证：

1. 使用 `SFI` scheme 完成 Debug 与 Release 编译和签名。
2. 验证 VPN 授权、启动、停止、后台恢复和按需连接。
3. 覆盖 Wi-Fi、蜂窝、USB/有线网络切换以及 IPv4/IPv6。
4. 验证 Rule、Global、Direct、节点切换和不同 UDP 能力节点下的诊断结果。
5. 验证本地与远程仪表盘、Widget、日志、连接列表和大字体布局。

STUN 结果只代表 UDP/NAT 路径，不能证明 HTTP、TCP 或所有分流规则。Apple 公共 API 也不提供透明接管 Personal Hotspot 客户端流量的能力，本预览不承诺“VPN 热点共享”。

## 提交真机修复

```bash
git clone --recurse-submodules --branch ios/preview-0.1.0 https://github.com/The-boond/ClashNL.git
cd ClashNL
git switch -c your-name/ios-device-test origin/ios/preview-0.1.0
```

请将真机修复 Pull Request 目标设为 `ios/preview-0.1.0`，并注明设备型号、iOS/Xcode 版本、测试配置、复现步骤和验证结果。
