# ClashNL Android 开发交接（2026-08-10）

## 1. 范围与当前状态

- 本交接仅涵盖 Android；iOS、Windows 与后台 API 本轮保持原状。
- 工作目录：`D:\Desktop\iosClashNL`
- Android 工程：`D:\Desktop\iosClashNL\Android`
- 当前分支：`codex/android-account-integration`
- 远端：`origin https://github.com/The-boond/ClashNL.git`
- 交接文档提交前的代码基线：`a4ce58e63e0a3f9a445b536fe9b8e56228dafdba`
- 文档创建前工作树干净，分支与远端同名分支同步。

## 2. 关键提交

按新到旧排列：

- `a4ce58e` `fix(ci): avoid Linux-only libbox test linker`
- `dbcc221` `feat(android): open plan details from compact cards`
- `23e5b3e` `fix(android): hide plan speed limits`
- `63c8cd2` `feat(android): compact plan purchase browsing`
- `758f4fd` `build(android): package Chinese and English locales`
- `64fb2e2` `merge: integrate Android offline latency testing`
- `ea326ca` `fix(android): move account sync off main thread`
- `37179d6` `feat(android): add plans and payment screen`
- `d4fa9e6` `feat(android): add plan purchase workflow`
- `decff94` `feat(android): add account and plan screen`
- `ae00b82` `feat(android): add XBoard account sync foundation`

## 3. Android 账户、套餐与支付页面

### 已完成

- XBoard 账户接口默认地址位于 `D:\Desktop\iosClashNL\Android\app\build.gradle.kts`，当前值为 `https://nextnexus.qzz.io`。
- Android 安装包只打包英文和简体中文资源：
  `androidResources.localeFilters += listOf("en", "zh-rCN")`。
- 套餐列表采用紧凑的双列卡片，仅展示：
  - 月付或一次性代表价格；
  - 套餐名称与流量；
  - 设备数量。
- 套餐列表不展示速率限制。
- 点击套餐卡片进入独立的“套餐详情”页面，不再在列表底部展开详情卡片。
- 详情页展示完整套餐说明、设备数量及全部可用周期。
- 点击周期进入订单确认和支付方式流程。
- 顶部返回键和 Android 系统返回键均可从详情页返回套餐列表。

### 主要文件

- `D:\Desktop\iosClashNL\Android\app\src\main\java\io\nekohasekai\sfa\compose\screen\account\PlanPurchaseScreen.kt`
- `D:\Desktop\iosClashNL\Android\app\src\main\res\values\strings.xml`
- `D:\Desktop\iosClashNL\Android\app\src\main\res\values-zh-rCN\strings.xml`
- `D:\Desktop\iosClashNL\Android\app\build.gradle.kts`

### 本机构建预览

以下为构建输出，未纳入 Git：

- `D:\Desktop\iosClashNL\Android\app\build\outputs\plan-list-preview.png`
- `D:\Desktop\iosClashNL\Android\app\build\outputs\plan-detail-preview.png`

## 4. VPN 停止状态节点测速

- 已将 `codex/android-offline-latency` 功能合并到当前账户集成分支，合并提交为 `64fb2e2`。
- 已包含统一结果模型、缓存仓库、调度器、Live Core 与 Offline Probe、网络身份、增量 UI 状态及相关测试。
- Core 长期规范源为 `D:\Desktop\iosClashNL\Android\Core\overlay\**` 及构建/验证脚本；生成的 `.build` 目录仅作为构建产物检查。
- 停止状态真机验证结果：
  - 服务状态保持“服务未启动”；
  - 节点组中可见“全部测速”；
  - 测速时可见“取消测速”；
  - 节点结果按完成进度显示“体验延迟”；
  - 未出现 VPN 权限弹窗；
  - 未出现 ClashNL VPN 已连接通知；
  - App 状态未切换为已连接。
- 当前 AAR 导出的 URL Test 为同步接口，后续改动仍应沿用仓库既有 gomobile/Go 构建流程。

## 5. 支付域名现状与后续迁移

### 已核查的现状

- XBoard `.env` 与运行时 `config("app.url")` 均为 `https://nextnexus.qzz.io`。
- 当前启用的支付配置为 `v2_payment` ID 9，支付驱动为 `EPay`。
- 该支付配置中的网关仍指向旧支付域名 `pay.nlaivpn.dpdns.org`，因此支付页面会显示该地址。
- `notify_domain` 当前为空。
- 新支付域名 `pay.nlmfnext.com` 在核查时尚未解析。
- 本轮只完成定位，未调整线上支付配置。

### 推荐的受控切换顺序

1. 为 `pay.nlmfnext.com` 添加 DNS-only IPv4 A 记录。
2. 在 HK-Web-02 配置 TLS 与反向代理。
3. 新旧支付域名同时代理到 HK-Web-01 当前 `epay-web`。
4. 更新启用中的支付配置 ID 9，将网关切换到新域名。
5. 保留旧回调地址和历史订单兼容。
6. 用小额真实订单验证收银台、二维码、异步回调、同步返回和账务结果。

当前链路：客户端 → HK-Web-02 边缘入口 → HK-Web-01 XBoard/支付；`epay-web` 仍运行在 HK-Web-01。

## 6. CI 失败原因与修复

- 旧失败任务：<https://github.com/The-boond/ClashNL/actions/runs/31338762056>
- 失败发生在 Android converter job，核心错误为：
  `invalid reference to runtime/pprof.parseProcSelfMaps`
- 原因是 Core 验证脚本重新执行了 Linux 环境下会触发链接边界的：
  `go test ./daemon ./experimental/libbox`。
- 已在 `D:\Desktop\iosClashNL\Android\scripts\verify-core.sh` 中拆分验证：
  - `go test ./experimental/libbox/internal/clashconv`
  - `go test ./daemon`
  - `go build ./experimental/libbox`
  - 使用 `sing-box check` 验证生成配置。
- 修复提交：`a4ce58e`。
- 最新成功任务：<https://github.com/The-boond/ClashNL/actions/runs/31340050326>
  - 总状态：success
  - Android converter：成功，约 52 秒
  - iOS converter：成功，约 11 秒
- Actions 中仍有 checkout/setup-go 的 Node 版本提示，不影响当前任务结果。

## 7. 构建与真机验证

### 已通过的命令

在 `D:\Desktop\iosClashNL\Android` 执行：

```powershell
.\gradlew.bat spotlessKotlinCheck
.\gradlew.bat :app:test
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assembleOtherDebug
.\gradlew.bat :app:assembleOtherLegacyDebug
bash scripts/verify-core.sh
```

### 真机

- 设备序列号：`EQYPFA9L7DBER4MN`
- 型号：`25060RK16C`
- 已安装版本：`0.1.0-alpha.1`
- 标准 APK：
  `D:\Desktop\iosClashNL\Android\app\build\outputs\apk\other\debug\ClashNl-Android-0.1.0-alpha.1-arm64-v8a-debug.apk`

### 套餐购买 UI 真机结果

- 套餐列表为双列布局，可滚动查看全部套餐。
- 列表中未显示 `Mbps`。
- 点击首个卡片可进入“套餐详情”。
- 详情中可见完整说明及月付、季付、半年付、年付等可用周期。
- 点击月付可进入“确认订单”和“支付方式”。
- 退出确认框并返回列表后，页面恢复为“选择套餐”，列表底部不再附加详情卡片。

## 8. GitHub Release

- Release：<https://github.com/The-boond/ClashNL/releases/tag/v0.1.0-alpha.1>
- 当前资产 SHA-256：
  - standard arm64：`f14ccd6ae8074076861903e8151856f7b319282f2d12318fdc197e922507d7a4`
  - legacy arm64：`99346c4a7129e0b9af14ed0610c01160a49dadc4c0852ff39dd29a6164d3aafa`
  - play arm64：`f1bc5b3d8330b554c3b59ce382f17e44a6b27d7fbeb2a529e38d0b058edb438b`
  - SHA 文件：`2b5629e9f2304b58d81edf36d6d64a13a0c07d43870f3557132b87b627132f8f`

当前仍反复替换同一个 `v0.1.0-alpha.1` Release 下的同名资产。面向更广范围分发前，建议提升版本号并创建新标签，使源码提交、CI 任务、APK 与校验值形成稳定的一一对应关系。

## 9. 下一会话建议顺序

1. 先执行 `git status --short --branch` 并确认位于 `codex/android-account-integration`。
2. 查看最新 Action `31340050326`，以 `a4ce58e` 作为功能代码基线。
3. 若继续 Android UI，优先做套餐详情页的视觉微调及中英文真机回归。
4. 若处理支付旧域名，严格按第 5 节顺序执行，先建立 DNS、TLS、代理和回滚点，再动支付配置。
5. 在支付域名切换后执行一笔小额完整支付链路验证。
6. 准备公开测试包时提升 Android 版本号并创建新 Release，避免继续覆盖旧标签资产。
7. 每个阶段继续采用小提交，提交前检查改动路径，排除订阅 URL、节点详情、Token、账户资料及私有配置。

## 10. 快速恢复命令

```powershell
Set-Location D:\Desktop\iosClashNL
git status --short --branch
git log --oneline --decorate -12
gh auth status
gh run view 31340050326

Set-Location D:\Desktop\iosClashNL\Android
.\gradlew.bat spotlessKotlinCheck :app:test
bash scripts/verify-core.sh
adb devices -l
```

