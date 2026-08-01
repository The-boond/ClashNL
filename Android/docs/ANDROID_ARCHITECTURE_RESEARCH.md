# ClashNL Android 技术调研与路线

更新日期：2026-07-28

## 真机反馈待办（2026-07-28）

这些项目来自当前 Android 真机回归：

- [x] 设置页增加应用内语言选择，并由 AndroidX per-app locale API
  立即应用系统默认、简体中文、繁体中文、English、Русский、فارسی。
- [x] 新建配置页增加独立的“从 URL 添加订阅”入口，复用
  `RemoteProfileRepository`，并保留二维码、QRS 和文件导入路径。
- [x] 普通 HTTP(S) 订阅二维码不再被扫描器的 sing-box deep-link
  预检查拦截；统一交给 `ProfileImportHandler` 识别。
- [x] 原配置首页拆为“订阅”导航，并在五项底部导航中央增加独立仪表盘。
- [x] 仪表盘支持订阅、当前代理、网络设置、代理模式、流量统计、网站测试、
  IP 信息、Clash 信息、系统信息九类模块，可分别开关并拖动排序。
- [ ] 完成一轮 Android 真机 UI 验收记录。

## 结论

ClashNL Android 不从零重写，也不切换到 Flutter。主线继续采用：

- Kotlin + Jetpack Compose；
- Android `VpnService`；
- sing-box Libbox；
- Room 保存配置索引，配置正文保存为应用私有文件；
- WorkManager 执行受网络约束的订阅更新。

当前工程已经是可构建的 sing-box-for-android 衍生工程。首要工作不是替换底座，
而是升级核心、收敛数据层、补齐订阅元数据、安全边界和自动化测试。

## 参考项目快照

研究仓库均以浅克隆放在 Android 工程的 `.build/research/`，该目录被 Git 忽略，仅作为
本地只读参考，不纳入 ClashNL 发布源码。

| 项目 | 本次研究提交 | 可借鉴部分 | 使用边界 |
|---|---|---|---|
| [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) | `d1d31eac836a` | Libbox/VpnService 主干、平台集成、配置编辑 | ClashNL 的直接底座；保留 GPL-3.0-or-later 义务和独立品牌 |
| [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) | `82b73a4bca24` | app/common/core/design/service 分层、订阅响应头、导入链接 | 借鉴架构和公开协议语义，不搬运品牌资产 |
| [v2rayNG](https://github.com/2dust/v2rayNG) | `cce2c9cdbd26` | 单订阅任务、网络约束、后台更新 | 借鉴调度策略；规避无鉴权本地监听器 |
| [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) | `5768494d8ae3` | 服务状态机、网络切换和服务恢复 | 借鉴生命周期边界 |
| [Hiddify](https://github.com/hiddify/hiddify-app) | `276a7effb004` | 订阅流量/到期信息、引导体验、测试组织 | 许可证带额外条件，仅借鉴交互和数据模型思想 |

补充参考：

- Android 数据层建议：
  <https://developer.android.com/topic/architecture/data-layer>
- Android 离线优先建议：
  <https://developer.android.com/topic/architecture/data-layer/offline-first>
- WorkManager：
  <https://developer.android.com/reference/androidx/work/WorkManager>
- VpnService：
  <https://developer.android.com/reference/android/net/VpnService>
- Android 备份安全：
  <https://developer.android.com/privacy-and-security/risks/backup-best-practices>

## 自研融合点

### 1. 单一订阅数据通道

所有“创建远程订阅、二维码导入、手动更新、定时更新”统一走
`RemoteProfileRepository`：

1. 下载订阅正文及受控响应头；
2. 解析 `subscription-userinfo`、`profile-update-interval` 等公开约定；
3. 将 Clash/Mihomo YAML 或 sing-box JSON 归一化；
4. 使用原子文件替换，避免进程终止留下半份配置；
5. 成功落盘后再更新 Room 元数据；
6. 仅在选中配置正文发生变化时请求服务重载。

### 2. 订阅元数据是提示，不是控制面

远端可建议刷新间隔和展示流量信息，但不能：

- 将自动更新间隔降到 15 分钟以下；
- 覆盖用户主动设置；
- 注入任意响应头或本地命令；
- 通过标题修改包名、应用品牌或权限。

### 3. 本地接口最小化

默认不开放跨应用可访问的 SOCKS、HTTP 管理端口。未来若增加本地控制 API：

- 显式绑定回环地址；
- 每次安装生成高熵令牌；
- 所有敏感操作鉴权；
- 禁止通过隐式 Intent 传递明文令牌；
- 对外暴露的 Android 组件逐项声明并测试 `exported`。

### 4. 隐私优先

订阅 URL、认证信息、配置正文和数据库不进入 Android 云备份或设备迁移。
发布签名材料不进入仓库；上游示例 keystore 只可用于本地样例，不作为 ClashNL
正式签名。

## 分阶段路线

### P0：可重复构建与安全基线

- [x] 盘点当前 Android 构建和依赖；
- [x] 升级 sing-box 核心到 `v1.14.0-beta.2`
  (`03c3bf4c01e7b1fd165d0c46ff376828fa878aab`)；
- [x] 配置和数据库退出系统备份；
- [x] 发布签名文件规则、统一 LF 行尾；
- [x] 订阅更新增加联网约束、指数退避和原子写入；
- [x] JVM 单元测试、Go 校验、Play/Other/Legacy Debug 构建全通过。

### P1：订阅产品能力

- [x] 响应头和正文注释元数据；
- [x] 已用/总流量、到期时间、更新时间；
- [ ] 订阅级刷新策略、失败原因和最后成功时间；
- [ ] 统一导入预览，导入前展示识别出的格式和风险字段。

### P2：稳定性与诊断

- VPN 服务显式状态机；
- 网络切换、睡眠唤醒、系统回收恢复测试；
- 配置热重载失败自动回滚；
- 脱敏诊断包，不包含订阅 URL、节点凭据和完整配置。

### P3：发布品质

- Play 版本与高级版本分离权限和组件；
- Baseline Profile、启动和耗电基准；
- 可复现发布、SBOM、依赖许可证清单；
- 真机矩阵：Android 8/10/12/14/15+，Wi-Fi/蜂窝/双卡/省电模式。

## 验收门槛

每一批变更至少满足：

1. `Core/clashconv` 单元测试通过；
2. Clash 示例生成后通过目标 sing-box 核心校验；
3. Android JVM 单元测试通过；
4. `assembleOtherDebug` 和 `assemblePlayDebug` 通过；
5. 配置更新异常时旧配置仍完整可用；
6. 日志和诊断结果不打印订阅 URL、令牌、证书或节点凭据。
