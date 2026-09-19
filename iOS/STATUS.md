# iOS 节点显示修复检查点

日期：2026-09-19。

## 目标与范围

修复从订阅页或代理组页选择节点后，仪表盘显示与核心实际选择不一致的问题。
用户授权同步修复 iOS 并上传 GitHub。基于 `ios/preview-0.1.0` 的 `03b0205`，
工作分支 `codex/ios-current-node-fix`；不合并 Android 历史，不创建或合并 PR。

## 已确认及修改

- 旧仪表盘取第一个可选组，规则模式仅排除名为 GLOBAL 的组；没有读取实际默认路由，也没有解析嵌套自动组。
- 旧节点页提前改选中项，并一直用待确认值覆盖核心回报；失败时可能留下与核心不一致的勾选。
- iOS 固定内核在 Selector 启动时同步恢复缓存选择，未发现 Android 那种启动后再恢复选择的调用顺序。因此保留 Apple 的核心启动方式。
- Packet Tunnel 在核心启动成功后提供已接受配置的路由摘要，IPC 仅传出站标签、组成员及模式规则，不传节点地址或凭据。
- 仪表盘沿实际默认路由及核心实时选择递归解析节点；支持自动组、核心省略的单成员组、显式模式规则。未知模式、未收到快照或循环关系不会伪装成已选节点。
- 断线清除旧快照；切换节点只显示核心确认的选择，同组点击顺序串行执行，失败保持真实状态。

主要文件：`Library/Network/RuntimeProxyRouting.swift`、`ExtensionProvider.swift`、
`ExtensionProfile.swift`、`CommandClient.swift`、`DashboardServiceCard.swift`、
`GroupListViewModel.swift` 及代理组视图。

## 验证与发布

- 本地 `go test ./...`（`iOS/Core/clashconv`）通过，`git diff --check` 通过。
- 新增 `scripts/test-proxy-selection.sh`，编译生产 Swift 路由解析器并运行回归用例，另解析七个接入文件的 Swift 语法；由独立 macOS GitHub Actions 执行，当前待执行。
- 回归覆盖：首次读取、非默认组选择、嵌套自动组、单成员组、实际模式规则、空/失效/循环选择、JSON5 配置、IPC 脱敏和回复超时竞争。
- 当前 Windows 没有 Swift/Xcode；没有完整应用编译、签名、模拟器或 iPhone 真机结果。不能将解析器测试当作整包或 VPN 实机验收。
- 发布目标：`ios-v0.1.0-preview.3` 源码预览版，不提供 IPA，不改变 Android Latest。发布前完成隐私扫描、macOS 测试并核对远端标签；发布后更新此记录。
- 本轮修改尚未提交。下一步：推送隔离工作分支运行 macOS 测试，通过后快进更新 iOS 分支并发布源码预览。
