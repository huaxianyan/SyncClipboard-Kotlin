# SyncClipboard Kotlin

一个原生 Android Kotlin 客户端，用于通过 SyncClipboard/WebDAV 数据格式手动同步文本、图片和单个文件。

## 为什么重写

Flutter 版磁贴的调用链是 `TileService → FlutterActivity → Flutter Engine → 网络请求`。应用进入 Android 缓存冻结状态后，每次点击磁贴都可能先支付完整的 Flutter 启动成本。

本项目改为：

```text
TileService → 轻量原生透明 Activity → ClipboardTransferService → OkHttp
```

Android 10+ 限制后台应用读取剪贴板，因此不能可靠地直接在 `TileService` 中读取内容。轻量 Activity 是有意保留的权限边界，但它不加载 Compose、Flutter，也不执行全局异步初始化；同步在窗口首次获得焦点后立即开始。

## 当前功能

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

## 当前范围

首个版本聚焦可靠的手动同步快速路径，目前只保存一个活动服务器配置。多服务器、Wi-Fi 自动切换、更新检查和日志查看将在核心同步路径验证稳定后再实现。

## 构建

要求：

- JDK 17
- Android SDK 36

```bash
./gradlew test
./gradlew assembleDebug
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

最低支持 Android 10（API 29）。

## 验收标准

1. App 进程被系统冻结或杀死后，点击磁贴应立即出现原生状态卡片，不等待 Flutter Engine。
2. 文本上传与下载结果和 Flutter 版使用相同的 `SyncClipboard.json` 格式及哈希算法。
3. 文件先传输 `file/{name}`，成功后再更新 `SyncClipboard.json`。
4. 网络失败必须显示明确错误，不能无限停留在加载状态。
