# ClashNL

项目已经按平台拆分为两个自包含工程：

```text
Android/    Android VpnService 客户端、Libbox 构建脚本、转换器和示例
iOS/        iOS/macOS/tvOS Apple 客户端、Xcode 工程、Libbox 脚本和示例
```

## Android

进入 [Android/README.md](Android/README.md)。

```powershell
cd D:\Desktop\iosClashNL\Android
.\gradlew.bat :app:assembleOtherDebug
```

## iOS

进入 [iOS/README.md](iOS/README.md)。

```bash
cd iOS
bash scripts/verify-core.sh
bash scripts/build-libbox.sh
open sing-box.xcodeproj
```

两个平台各自保存 `Core/`、`Examples/`、`scripts/`、隐私说明和品牌源图，
因此可以单独复制、构建和维护。仓库根目录只保留版本控制、CI、总览和总许可证。

ClashNL 主 Logo：

- [Android/Branding/ClashNL-logo-1024.png](Android/Branding/ClashNL-logo-1024.png)
- [iOS/Branding/ClashNL-logo-1024.png](iOS/Branding/ClashNL-logo-1024.png)

代码按 [GPL-3.0-or-later](LICENSE) 分发。
