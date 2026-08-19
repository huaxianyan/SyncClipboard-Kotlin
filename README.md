# SyncClipboard Kotlin

一个原生 Android Kotlin 客户端，用于通过 SyncClipboard/WebDAV 数据格式同步文本、图片和文件。

## 项目说明

本项目参考 [SyncClipboard Flutter](https://github.com/Jeric-X/SyncClipboard) 的核心同步逻辑，并保持 `SyncClipboard.json`、内容类型和 SHA-256 算法兼容。

客户端采用原生 Kotlin 和 Android 平台能力实现，目标是提供更好的原生体验与性能。快速设置磁贴使用轻量原生 Activity，不依赖 Flutter 或 Compose 初始化；主界面使用 Jetpack Compose Material 3。

## 当前功能

- 首页显示服务器连接状态和上次成功同步时间
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

## 规划

应用界面将分为首页、剪贴板历史和设置三个部分。当前版本开放首页与设置，后续将逐步加入剪贴板历史、自动同步、同步内容范围以及图片和文件保存位置等功能。

## 构建

要求：

- JDK 17
- Android SDK 35

```bash
./gradlew test
./gradlew assembleDebug
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

最低支持 Android 10（API 29）。
