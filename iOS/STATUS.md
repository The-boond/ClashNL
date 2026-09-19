# iOS 节点显示修复检查点

日期：2026-09-19。

## 目标与范围

修复从订阅页或代理组页选择节点后，仪表盘显示与核心实际选择不一致的问题。
用户授权同步修复 iOS 并上传 GitHub。基于 `ios/preview-0.1.0` 的 `03b0205`，
工作分支 `codex/ios-current-node-fix`；不合并 Android 历史，不创建或合并 PR。
用户同时要求处理失败的 iOS Actions；发布前需让回归检查和完整模拟器编译通过。

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
- 新增 `scripts/test-proxy-selection.sh`，编译生产 Swift 路由解析器并运行回归用例，另解析八个接入文件的 Swift 语法；22 项检查通过。
- 回归覆盖：首次读取、非默认组选择、嵌套自动组、单成员组、实际模式规则、空/失效/循环选择、JSON5 配置、IPC 脱敏和回复超时竞争。
- [macOS 工作流 35436150575](https://github.com/The-boond/ClashNL/actions/runs/35436150575) 已成功：`selection-regression`、`simulator-build` 均通过，日志确认 `BUILD SUCCEEDED`。编译了固定核心和完整 SFI 模拟器 Debug 应用，禁用的仅是签名，没有跳过构建检查。
- 首轮 `ac64f4b` 曾因原有 `CardManagementSheet.swift` 的 View 级 `fontWeight` 不支持 iOS 15 而失败；`eeb0eb8` 将相同样式移到 Text，保留 iOS 15 支持，随后完整构建通过。
- Swift/构建验证对应代码提交 `eeb0eb84c2363d46983ecb14a699d0e870d2d010`；之后仅更新说明文档。`build-libbox.sh` 新增可选 `APPLE_PLATFORM`，CI 只构建模拟器核心，默认完整 Apple 构建不变。
- 隐私：当前源码、增量提交及图片差异已检查；没有新增设备截图，未命中已确认的个人 IP、用户名、工作目录或凭据；提交均使用 GitHub noreply 身份。
- 尚未完成：Apple 签名、iPhone 实机 VPN/出口与后台恢复验证。模拟器编译不能代替这些测试。
- 发布目标：`ios-v0.1.0-preview.3` 源码预览版，不提供 IPA，不改变 Android Latest。下一步：提交验证记录，快进更新 iOS 分支并创建预览发布，核对远端后补充结果。
