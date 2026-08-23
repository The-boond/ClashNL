# ClashNL

ClashNL 是以 Mihomo 为核心的 Android 代理客户端。`v1.1.0` 起，Android
版本不再包含账户登录、套餐购买或账户同步模块，直接管理本地配置与
Clash/Mihomo YAML 订阅。

## Android

工程位于 [`Android/`](Android/)，主要能力包括：

- 原生 Mihomo 单核运行时
- Clash/Mihomo YAML 订阅导入、更新与校验
- VPN 停止时选择节点、进行延迟测试和预选规则/全局/直连模式
- Android 系统代理与完整虚拟网卡两种网络模式
- IPv4、IPv6、DNS、分应用代理与连接/流量查看

构建 Debug APK：

```powershell
cd Android
.\gradlew.bat :app:assembleOtherDebug
```

构建正式签名 APK：

```powershell
.\gradlew.bat :app:assembleOtherRelease
```

更多说明见 [`Android/README.md`](Android/README.md)。

## Apple 平台

iOS、macOS 与 tvOS 工程保留在 [`iOS/`](iOS/)，与 Android 工程独立维护。

## 许可证

项目按 [GPL-3.0-or-later](LICENSE) 分发。
