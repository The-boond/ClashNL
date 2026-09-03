# ClashNL Android v1.1.3

本版本新增真实物理上游与 VPN 出口诊断，并修复切网、节点切换后的陈旧结果问题。

## 主要变化

- 自动识别当前真实非 VPN 上游，包括 Wi-Fi、蜂窝网络、以太网、USB、蓝牙及可用的新平台传输类型。
- 仪表盘分别显示物理接口名称、本地接口地址、物理直连公网出口和当前 Mihomo 路径公网出口。
- 物理直连查询固定使用捕获到的 Android `Network`；VPN 查询固定使用 ClashNL 自己的本地 Mihomo HTTP 代理，不再混用系统默认网络。
- Wi-Fi/蜂窝等物理上游变化时立即清除旧结果；用户刷新后只查询新的上游。节点或模式切换后，旧的代理出口会失效并在运行条件满足时刷新。
- 保留 HyperOS 已验证的 TUN 建立后底层网络绑定时序，避免热点下游断网；系统代理模式不错误套用 TUN 的底层网络更新。
- 更新中英文界面和隐私说明。

## 真机验证

- Android 16 / HyperOS 真机已验证 Wi-Fi `wlan0` 与蜂窝 `ccmni0` 自动切换。
- Wi-Fi 物理出口为 `[network address removed]`，蜂窝物理出口为 `[network address removed]`；两种入口下当前日本节点出口均为 `[network address removed]`。
- USB 共享客户端在 VPN 开关前后均显示 `[network address removed]`，证明 Android 普通 `VpnService` 不会透明接管热点/USB 转发流量。本版本没有宣称支持免 Root 的透明 VPN 热点。
- 109 项 Android 单元测试、Spotless、Lint Vital、Release APK、AndroidTest 打包和 Android 5 兼容编译通过。

## 使用边界

- “虚拟网卡/全局”作用于已经进入本机 VPN 的流量，不等于热点客户端也会进入 VPN。
- Android 透明 VPN 热点通常需要 Root 改写转发规则，或依赖厂商提供的系统级共享 VPN 能力。
- “系统代理”只能影响遵循 Android HTTP 代理设置的应用和协议，不能替代全协议 TUN。
