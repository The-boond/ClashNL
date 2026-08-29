# ClashNL iOS 0.1.0 Preview 1

> **源码预览，尚未经过 Xcode 编译、模拟器或 iPhone 真机验证。**
>
> 本次开发环境为 Windows，无法运行 Apple 的 Xcode、Network Extension
> 签名流程和 iOS 真机测试。因此，本版本可能存在编译错误、签名问题、界面兼容
> 问题或 VPN 数据通路故障，目前不应视为可直接日常使用的稳定版本。

本预览版根据 ClashNL Android v1.1.2 的产品能力重新整理 iOS 体验，但保留
SwiftUI、系统 Tab、NavigationStack、Network Extension、Widget、按需连接、
文件与二维码导入等 Apple 平台路径，没有直接复制 Android Material 界面或
Android 专属网络能力。

## 主要变化

- iPhone 底栏收敛为“仪表盘、订阅、设置”三个主要入口。
- 日志、活动连接、代理组和网络诊断改为设置内的原生分层导航。
- 订阅页增加“代理组与节点”入口。
- 代理组使用适合 iPhone 的逐层列表和大触控区域选择节点。
- 首次进入代理组时自动发起延迟测试。
- 延迟颜色与 Android v1.1.2 对齐：250 ms 以内绿色、350 ms 以内蓝色、
  600 ms 以内橙色、更高延迟为红色。
- 仪表盘同时显示当前代理组与节点，并在 Rule 模式下优先忽略合成 `GLOBAL` 组。
- 连接页增加系统搜索、加载状态和空状态。
- 截图测试路径已适配新的“设置 → 日志”导航结构。

## 尚未完成的验证

期待有 macOS、Xcode、Apple 开发者签名和 iPhone 真机环境的开发者协助测试：

1. 使用 `SFI` scheme 完成 Debug 与 Release 编译。
2. 安装后完成 Network Extension/VPN 权限授权。
3. 分别测试远程订阅、文件导入、二维码导入和已有配置升级。
4. 验证 VPN 启停、DNS、IPv4/IPv6、蜂窝网络与 Wi-Fi 切换。
5. 验证 Rule、Global、Direct 模式及代理组/节点切换后的真实出口。
6. 验证节点延迟测试、前后台切换、按需连接和 Widget 控制。
7. 验证连接列表、日志、崩溃报告以及辅助功能和大字体布局。

## 提交真机修复

建议从本预览分支创建测试分支，以避免与仓库当前默认分支的历史差异混在一起：

```bash
git clone --recurse-submodules --branch ios/preview-0.1.0 https://github.com/The-boond/ClashNL.git
cd ClashNL
git fetch origin
git switch -c your-name/ios-device-test origin/ios/preview-0.1.0
```

修改后只提交相关文件，推送到自己的分支或 Fork，并向
`ios/preview-0.1.0` 发起 Pull Request。请在 PR 中注明设备型号、iOS/Xcode
版本、测试配置、复现步骤和验证结果。
