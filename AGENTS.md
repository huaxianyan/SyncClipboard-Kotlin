# AGENTS.md

## 项目目标

SyncClipboard Kotlin 是 SyncClipboard Flutter Android 客户端的原生 Kotlin 重写。

## 核心约束

- 快速设置磁贴路径不得依赖 Flutter、Compose 或重量级全局初始化。
- Android 10+ 的剪贴板读取必须发生在获得焦点的轻量 Activity 中，不要退回后台读取剪贴板的脆弱方案。
- 网络和文件 I/O 不得运行在主线程。
- 文件上传顺序为先上传数据文件，再更新 `SyncClipboard.json`，避免元数据指向不存在的文件。
- 保持与 Flutter 版的 JSON 字段、类型名称和 SHA-256 算法兼容。
- 优先使用 Android 平台能力和少量稳定依赖，避免为简单功能引入框架。

## 语言

代码标识符使用英文；用户界面、文档和注释默认使用简体中文。
