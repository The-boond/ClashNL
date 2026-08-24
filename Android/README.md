# ClashNl for Android

ClashNl Android 是现有 ClashNl 项目的 Android 客户端。工程基于当前
Android `VpnService` 与原生 Mihomo bridge 实现，配置始终以 Clash/Mihomo
YAML 保存，并在导入、编辑、更新及启动前由 Mihomo 原生解析器校验。应用
不包含账户登录、套餐购买或账户同步模块。

## 已接入

- Android `VpnService`、前台服务通知、快捷设置磁贴
- 本地文件、HTTP(S) 订阅、二维码/QRS 导入
- `clash://install-config`、`clashmeta://install-config` 和
  `clashnl://import-remote-profile` 链接
- Clash/Mihomo YAML 原样导入、原生校验与原子保存
- 远程订阅首次拉取、手动更新和后台更新时校验后替换
- 解析订阅响应头及正文注释中的流量、总量、到期时间与建议刷新间隔，
  并在配置卡片展示最新订阅状态
- 独立“订阅”导航和居中的可配置仪表盘；九类模块均可开关和拖动排序
- 应用内语言选择，以及 HTTP(S) URL/普通订阅二维码直接导入
- Mihomo 支持的 Shadowsocks、VMess、VLESS Reality、Trojan、Hysteria 2、
  TUIC、AnyTLS、WireGuard 等节点与 provider
- 首次启动 VPN 流量披露；未确认前不会发起 VPN 权限请求
- 独立应用 ID `com.clashnl.android` 和 ClashNl 品牌资源

运行配置保存在应用私有目录中；旧版本遗留的 sing-box JSON 只作为用户
数据保留，不会被送入 Mihomo 运行时。升级时会清除旧账户凭据与其托管订阅。

## 环境

- JDK 17（OpenJDK）
- Go 1.25.12 或更新的兼容版本
- Android SDK 37.1
- Android NDK `28.0.13004108`
- Android Studio 或命令行 Gradle

## 构建

Gradle 会为目标 ABI 构建 Mihomo bridge，然后打包 APK：

```powershell
cd D:\Desktop\iosClashNL\Android
.\gradlew.bat :app:assembleOtherDebug
```

生成使用 `app/release.keystore` 签名的 GitHub Release APK：

```powershell
.\gradlew.bat :app:assembleOtherRelease
```

Google Play 变体：

```powershell
.\gradlew.bat :app:bundlePlayRelease
```

Release 签名参数写入 `local.properties`，示例：

```properties
sdk.dir=C\:\\Users\\USER\\AppData\\Local\\Android\\Sdk
KEYSTORE_PASS=CHANGE_ME
ALIAS_NAME=clashnl
ALIAS_PASS=CHANGE_ME
```

签名文件放在 `app/release.keystore`。这些文件均由 `.gitignore`
排除。

## 验证

```powershell
.\gradlew.bat :app:spotlessCheck :app:testOtherDebugUnitTest `
  :app:lintVitalOtherRelease :app:assembleOtherRelease `
  :app:assembleOtherDebugAndroidTest
```

原生核心来自 `Core/mihomo-android` 子模块，应用桥接代码位于
`Core/mihomo-bridge` 与 `mihomo-bridge`。导入测试可使用仓库中的
`Examples/clash-modern.yaml`。安装 debug APK 后，应覆盖 IPv4/IPv6、
TCP/UDP、DNS、防休眠、Wi‑Fi/蜂窝切换、锁屏恢复、订阅更新、分应用代理
和长期前台运行。

## 发布

1. Play Console 将 VPN 设为核心功能并提交 `VpnService` 声明。
2. 商品详情清楚说明 `VpnService` 用途，并录制连接流程审核视频。
3. 仅为 Play 版本提供符合商店要求的加密隧道配置。
4. 将当前目录的 `PRIVACY.md` 发布为公开 URL，并完成 Data safety 表单。
5. 随 APK/AAB 发布对应源代码、构建脚本、GPL 许可证和修改说明。

来源、固定提交及许可证说明见 [NOTICE](NOTICE)。
