# SyncClipboard Kotlin

SyncClipboard Kotlin 是面向 Android 的原生 SyncClipboard 客户端，用于在手机与 SyncClipboard Server 之间同步文本、图片和文件。

项目使用 Kotlin 与 Jetpack Compose 构建主体界面，并保留轻量原生快速路径。主体应用可以独立完成手动同步；需要后台自动同步时，可按需安装无界面的 LSPosed 系统扩展。

## 下载

请从 [GitHub Releases](https://github.com/huaxianyan/SyncClipboard-Kotlin/releases) 下载正式版本：

- `app-release.apk`：主体应用，提供手动同步和全部设置
- `system-extension-release.apk`：可选系统扩展，仅用于后台自动同步

## 功能

### 手动同步

- 通过快速设置磁贴上传或下载剪贴板内容
- 上传和下载文本、图片及单个文件
- 从其他应用分享文本、图片或文件到 SyncClipboard
- 下载 `Group` 文件组并安全解压
- 长文本自动改用数据文件传输
- 显示服务器连接状态和最近一次成功同步

### 自动同步

- 解锁期间自动上传本地剪贴板文本
- 通过 SignalR 实时接收远端更新
- 自动接收远端文本、图片和文件
- 图片与文件分别保存到用户选择的目录
- 可限制为仅通过 Wi-Fi 自动同步
- 锁屏后暂停网络连接，解锁后自动恢复
- 临时断网或等待 Wi-Fi 时保留最新一条普通文本，恢复后继续上传
- 忽略应用标记的敏感剪贴板内容
- 用绿、黄、红三种状态区分正常运行、预期等待和同步错误

自动同步是可选功能，需要安装配套系统扩展，并在支持 libxposed API 102 的 LSPosed 环境中为 SystemUI 启用作用域。系统扩展不包含界面、网络请求或独立配置。

### 服务器与协议

- 管理、编辑和切换多个服务器方案
- 支持 HTTP Basic Auth
- 可选择信任自签名 HTTPS 证书
- 兼容 `SyncClipboard.json`、内容类型和 SHA-256 校验规则
- 支持 SyncClipboard Server 的 SignalR 推送

## 使用方式

### 主体应用

1. 安装主体 APK。
2. 在「设置」中添加并选择 SyncClipboard Server。
3. 使用首页检查服务器连接。
4. 从快速设置磁贴手动上传／下载，或从其他应用分享到 SyncClipboard。

主体应用最低支持 Android 10（API 29）。手动同步不需要 Root、LSPosed 或系统扩展。

### 系统扩展

1. 安装与主体应用配套发布的系统扩展 APK。
2. 在 LSPosed 中启用模块，并将作用域设为 SystemUI。
3. 按模块管理器要求重新启动设备。
4. 回到主体应用，在「设置」中确认扩展连接后开启后台自动同步。

主体应用与系统扩展必须来自同一套正式发布产物。详细设计及安全边界见 [架构说明](docs/architecture.md)。

## 技术文档

- [架构说明](docs/architecture.md)：模块职责、同步链路、网络生命周期和安全边界
- [构建与发布](docs/build.md)：开发环境、构建命令、签名配置和 GitHub Actions

## 参考项目

本项目在协议兼容、功能设计和系统扩展方案上参考了以下开源项目：

- [Jeric-X/SyncClipboard](https://github.com/Jeric-X/SyncClipboard)：SyncClipboard 桌面客户端、服务端和协议实现
- [bling-yshs/sync-clipboard-flutter](https://github.com/bling-yshs/sync-clipboard-flutter)：Flutter 版移动客户端
- [shaklow/syncclipboard-xposed](https://github.com/shaklow/syncclipboard-xposed)：基于 LSPosed 的 Android 剪贴板同步实现

感谢这些项目及其贡献者。本项目是独立实现，并非上述项目的官方 Android 客户端。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)（`GPL-3.0-only`）发布。
