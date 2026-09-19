# 仓库隐私与发布状态

## Android v1.1.5 发布检查点（2026-09-19）

- 授权与范围：清理 IP 等隐私并发布新 Android 版本到 GitHub；不改动 Apple 分支，不删除旧版本。
- 版本：`VERSION_NAME=1.1.5`、`VERSION_CODE=18`；节点修复基线 `2acbcfb`，发布说明见 [v1.1.5](releases/v1.1.5.md)。
- 隐私：源码、本次提交、四张现有商店截图及五个最终 APK（含解压条目）已检查，未命中已确认的个人地址、用户名、工作目录路径或凭据。测试中的公网 A/AAAA 地址已替换为文档保留地址。运行配置、密钥、设备截图与本地审计资料不进入提交或发布附件。
- 验证通过：`:app:spotlessCheck :app:testOtherDebugUnitTest :app:lintVitalOtherRelease :app:assembleOtherRelease`；118 项 JVM 测试零失败。五个正式 APK 签名均有效且与已发布 v1.1.4 证书相同；版本号/代码已回读确认，生成五包 SHA-256 文件。
- 手机已覆盖安装 arm64 正式包并保留数据；修复前后的完整 UI/出口验证见下方开发检查点，正式包启动复查尚待手机解锁。
- 发布准备：五种架构 APK 加 `SHA256SUMS-1.1.5.txt`；发布前核对远端标签和上传附件摘要，完成后更新此检查点。分支 `codex/android-account-integration`，版本及发布文档待本地提交。

## 当前开发检查点：仪表盘节点选择（2026-09-19）

- 目标：首次从仪表盘以外的入口选节点后，仪表盘按运行中的默认路由选择链显示，不能把未使用的地区/分流组当作主组。
- 已确认主因：控制器先发布 `Running`，VPN 服务随后才恢复保存节点及模式；仪表盘在恢复前读取默认节点，恢复后没有新状态通知。真机旧版重现“主组美国、仪表盘日本、出口美国”。仅修正主组识别仍复现，修正启动顺序后消失。
- 另一显示缺陷：旧实现优先使用选择文件中首个与核心选择一致的组；地区/服务组可能因此被误当作主组。
- 修改：`MihomoStartRequest.beforeReady` 在启动发布 `Running` 前恢复选择和模式，虚拟网卡/系统代理都接入。通过控制器读取 `/rules` 首个启用 `Match` 的目标，沿实时 `/proxies` 解析；移除显示逻辑对历史选择文件的依赖。无 Match 时保留兼容回退；规则分流可以产生不同出口，详见 [Android 说明](../Android/README.md#仪表盘当前节点)。
- 代码：`bg/MihomoVpnService.kt`、`mihomo/MihomoModels.kt`、`AndroidMihomoController.kt`、`MihomoController.kt`、`MihomoApiClient.kt`、`DashboardViewModel.kt`；JVM 回归和 `MihomoNativeBridgeInstrumentedTest`，以及明确 AndroidJUnitRunner 的测试配置。
- 本地通过：Java 17 下 `:app:spotlessApply :app:spotlessCheck :app:testOtherDebugUnitTest :app:assembleOtherDebug :app:assembleOtherDebugAndroidTest :app:lintVitalOtherRelease`；118 项 JVM 测试零失败。Debug APK、原生测试 APK 均已构建。
- 扩展检查限制：完整 `lintOtherDebug` 在 Java 17 的依赖分析中因 `List.removeLast()` 缺失崩溃；临时改用本机 JBR 25 又触发既有模块 Java 17/Kotlin 25 目标不一致。未禁用检查或修改工具链；不能称完整 Lint 通过。
- 真机通过：用户连接手机后覆盖安装修复 Debug 包，保留应用数据；同配置首次启动显示美国且出口美国；运行中从订阅页切到日本，显示及出口同步；停止后从订阅页选美国再启动，首次显示及出口仍正确。全程未通过仪表盘节点入口补选。测试结束恢复原订阅、原美国节点选择及原先停止状态，删除本轮临时 UI 转储。
- 原生仪器测试已编译但未执行：手机拒绝辅助测试 APK 安装（`INSTALL_FAILED_USER_RESTRICTED`）；未绕过设备确认。已完成上述真实 UI/网络路径验证，但未做长时间网络或系统代理真机回归。
- 开发提交：`2acbcfb`，分支 `codex/android-account-integration`；随后获得发布授权，按上方 v1.1.5 检查点继续。未修改订阅正文。

## 历史隐私与发布检查点

日期：2026-09-10。

## 目标与授权

清除仓库及发布资料中的个人信息。用户已明确授权历史重写、远端强制更新和旧版本删除；仅保留最新 v1.1.4。iOS/macOS 由用户自行开发，不新开合并请求。

## 已完成

- 三个公开开发分支已完成历史脱敏并回读核验；原有标签历史也已脱敏。
- 历史中的真实网络地址、个人邮箱、本机用户名和绝对路径已清理；删除含内部操作细节的旧交接记录。
- 17 个旧 Release 及对应标签已删除，仅保留 v1.1.4。
- PR #1 已关闭且未合并；自动合并原本即为关闭状态。保留开发分支及 iOS/macOS 源码。
- 删除 35 份本地真机截图/UI 转储，加入忽略规则；4 张现有商店截图未发现本次涉及的个人信息。
- 31 次工作流日志已扫描，未命中本次已确认的敏感值；无工作流附件。Issue/PR 正文及评论未发现本次涉及的敏感值。
- 本仓库后续 Git 提交使用已核实的 GitHub 公开身份及 noreply 邮箱。

## 最新安装包

旧 v1.1.4 APK 原生库中发现本机构建路径残留，已删除并替换。构建脚本增加 `-trimpath`，四种架构的原生核心已强制重新构建。五个最终 APK 的压缩条目与包体扫描均无已确认敏感值命中，APK 签名通过且证书与原发布版相同。五个 APK 和新的 SHA-256 校验文件均已上传，GitHub 返回的大小与 SHA-256 全部匹配本地已验证产物。

## 验证与剩余边界

- 重写后的历史对象扫描无已确认敏感值命中；21 个原有分支/标签树对比确认变更仅涉及隐私文档。
- `:mihomo-bridge:buildMihomoLibraries --rerun-tasks` 成功，四个架构任务全部执行。
- `:app:spotlessCheck :app:testOtherDebugUnitTest :app:lintVitalOtherRelease :app:assembleOtherRelease` 成功；未受影响的任务复用 Gradle 已有结果，原生库与 APK 已重新生成。
- 本次尚未重新执行真机 VPN/ChatGPT 流程，历史真机结果不作为此次重新测试的证据。
- 远端最终核对：1 个 Release、1 个标签、6 个已验证附件、0 个未关闭 PR。
- GitHub 的旧提交缓存与只读 PR 引用需平台处理；已有第三方 fork/克隆无法由本仓库强制删除。不能据此宣称互联网所有副本已清除。
- 历史已重写，不要将旧克隆直接合并或推回。后续开发应使用清理后的历史。

工作分支：`codex/android-account-integration`。构建修复提交：`3853e68`。下一步：由仓库所有者向 GitHub Support 请求清理旧提交缓存及 PR 内部引用；草稿已经本地准备，尚未发送。
