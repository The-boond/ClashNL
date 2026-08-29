# ClashNL Android v1.1.2

本版本重点修复 Android 端订阅与节点选择交互。

## 主要变化

- Rule 模式不再显示不会覆盖实际规则选路的 Mihomo 合成 `GLOBAL` 组；Global 模式仍保留该组。
- 订阅页改为“订阅 → 代理组 → 节点”三级结构，VPN 运行时也可直接切换实际代理组节点。
- 第一次展开订阅卡片时自动依次测试可选代理组内的节点。
- 延迟颜色调整为：250 ms 以内绿色、251–350 ms 蓝色、351–600 ms 橙色、超过 600 ms 或超时红色。
- 仪表盘卡片名称调整为“当前代理组与节点”。

## 验证

- Android Dashboard 相关单元测试通过。
- Other Debug arm64 APK 构建通过并覆盖安装到 Android 16 真机。
- 真机在 VPN 运行时从订阅页将 `NL` 切换到“新加坡-01”，仪表盘同步更新，实际出口验证为 `[network address removed] / SG · SIN`。
