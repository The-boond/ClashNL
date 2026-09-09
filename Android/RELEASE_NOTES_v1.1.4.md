# ClashNL Android v1.1.4

## 修复内容

- 修复在节点页进行离线测速时立即启动 VPN，服务可能长期停留在“启动中”的问题。
- 修复规则模式下仪表盘误把自动选择/故障转移等辅助组显示为当前节点的问题。
- 启动 VPN 后优先显示并沿用停机状态下由用户选择的节点。
- 原生核心构建去除开发环境路径，发布说明只保留脱敏的验证结论。

## 验证

- `spotlessCheck`、`testOtherDebugUnitTest`、`assembleOtherRelease` 均通过。
- Android 真机复测：从节点页立即启动 VPN 成功，仪表盘正确显示所选节点。
- 已验证实际 Mihomo 路径出口与所选测试节点一致。
- 经测试 TCP 节点冷启动 ChatGPT 正常进入聊天界面，未再出现 SSL/网络配置错误页。

各 APK 的 SHA-256 见 `SHA256SUMS-1.1.4.txt`。
