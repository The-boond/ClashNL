# ClashNL iOS 0.1.0 Preview 3

> **源码预览，不提供 IPA。已通过 iOS 模拟器 Debug 无签名编译，尚未完成 Apple 签名和 iPhone VPN 实机验证。**
>
> 由于环境限制，不一定能用，期待大佬进行实机测试。

## 修复

- 修复仪表盘使用第一个可选代理组，导致从其他页面选择节点后显示错误的问题。
- 仪表盘改为读取正在运行的隧道路由信息，并沿核心确认的选择解析最终节点，支持嵌套自动组。
- 断线或切换配置时清除旧节点快照，避免沿用上一会话的显示。
- 节点页不再将尚未被核心确认的点击结果显示为已选中；同组操作按顺序执行，失败保留真实选中状态。
- 新增 Swift 路由选择回归测试与独立 macOS 检查流程。
- 修正仪表盘管理页的 iOS 15 编译兼容问题。

规则模式允许不同网站走不同分流规则；仪表盘节点代表默认规则路径。
新增进程间路由摘要不包含服务器地址、订阅内容或密码。

## 验证

- 22 项 Swift 路由选择回归检查通过。
- 固定版本 Libbox 模拟器核心及完整 SFI 应用的无签名编译通过。
- Go 配置转换器测试通过，源码及提交已进行隐私检查。
- [本次成功的 macOS 工作流](https://github.com/The-boond/ClashNL/actions/runs/35436150575)包含回归检查和完整应用编译；没有跳过失败检查或提高最低 iOS 版本。

## 获取源码

```bash
git clone --recurse-submodules --branch ios-v0.1.0-preview.3 https://github.com/The-boond/ClashNL.git
cd ClashNL/iOS
```

构建要求及步骤见 [iOS README](https://github.com/The-boond/ClashNL/blob/ios/preview-0.1.0/iOS/README.md)，
验证记录见 [iOS STATUS](https://github.com/The-boond/ClashNL/blob/ios/preview-0.1.0/iOS/STATUS.md)。

在签名后的 iPhone 应用上仍需验证：首次从订阅页选节点、停止后重启、嵌套自动组、
失败/连续切换、后台恢复和配置切换。测试配置、截图和日志请先脱敏。
