# SyncClipboard Kotlin

一个原生 Android Kotlin 客户端，用于通过 SyncClipboard/WebDAV 数据格式同步文本、图片和文件。

## 项目说明

本项目参考 [SyncClipboard Flutter](https://github.com/bling-yshs/sync-clipboard-flutter) 的核心同步逻辑，并保持 `SyncClipboard.json`、内容类型和 SHA-256 算法兼容。

客户端采用原生 Kotlin 和 Android 平台能力实现，目标是提供更好的原生体验与性能。快速设置磁贴使用轻量原生 Activity，不依赖 Flutter 或 Compose 初始化；主界面使用 Jetpack Compose Material 3。

## 当前功能

- 首页显示服务器连接状态和上次成功同步时间
- 多服务器方案的新增、编辑和切换
- 上传和下载快速设置磁贴
- 文本上传、下载及系统剪贴板写入
- 超过 10,240 字符的文本以数据文件传输
- 图片和单个文件上传、下载
- `Group` ZIP 下载及安全解压
- 从其他应用通过 Android 分享上传文本、图片或文件
- HTTP Basic Auth
- 可选信任自签名 HTTPS 证书
- SHA-256 内容校验
- Android 13+ 可在应用内请求添加磁贴
- 可选的无界面系统扩展，支持后台检测、上传和接收文本
- 主体应用统一管理系统扩展状态、自动同步设置和卸载入口

## 系统扩展

`system-extension` 是可选的无界面伴生 APK。它只在 SystemUI 中监听剪贴板变化，并通过 Binder 将文本交给主体应用的独立 `:sync` 进程；服务器配置、网络请求和同步记录仍由主体应用管理。

系统扩展需要支持 libxposed API 102 的 LSPosed 环境，并在 SystemUI 作用域启用。主体应用只有在收到系统扩展的有效连接后，才允许开启高级自动同步。

当前高级模式支持文本自动上传，并通过 SyncClipboard Server 的 SignalR 推送实时接收文本；连接建立时会补查一次远端状态，不支持推送或连接失败时最多每 5 分钟检查一次。手机锁定后停止远端连接，解锁后自动重连并补同步。

## 规划

当前版本开放首页与设置，后续将逐步加入剪贴板历史、图片与文件自动同步以及自定义保存位置等功能。

## 构建

要求：

- JDK 17
- Android SDK 35

```bash
./gradlew test
./gradlew :app:assembleDebug :system-extension:assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
system-extension/build/outputs/apk/debug/system-extension-debug.apk
```

主体应用可以独立安装。需要高级自动同步时，再安装系统扩展 APK，并在模块管理器中启用。

## 签名

本机测试和正式发布统一读取：

```text
%USERPROFILE%\.gradle\syncclipboard-signing.properties
```

主体 APK 与系统扩展 APK 使用同一生产证书。签名文件和密码不得提交到仓库；CI 可通过 `SYNC_CLIPBOARD_STORE_FILE`、`SYNC_CLIPBOARD_STORE_PASSWORD`、`SYNC_CLIPBOARD_KEY_ALIAS` 和 `SYNC_CLIPBOARD_KEY_PASSWORD` 环境变量提供签名。缺少生产签名时允许执行 Debug 构建，但 Release 构建会直接失败。

最低支持 Android 10（API 29）。
