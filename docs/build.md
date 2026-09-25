# 构建与发布

## 环境要求

- JDK 17
- Android SDK 35
- Android 10（API 29）或更高版本的测试设备

项目使用 Gradle Wrapper，不需要单独安装 Gradle。

## Debug 构建

运行单元测试、Lint 和 Debug 构建：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

只构建主体应用和系统扩展：

```bash
./gradlew :app:assembleDebug :system-extension:assembleDebug
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
system-extension/build/outputs/apk/debug/system-extension-debug.apk
```

主体应用可以独立安装。系统扩展只在需要高级自动同步时安装。

## 发布签名

主体应用与系统扩展必须使用同一发布证书，但两者保持独立 UID。签名配置不应提交到仓库。

本地构建默认读取：

```text
%USERPROFILE%\.gradle\syncclipboard-signing.properties
```

文件格式：

```properties
storeFile=C:\path\to\syncclipboard-release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

也可以使用环境变量：

```text
SYNC_CLIPBOARD_STORE_FILE
SYNC_CLIPBOARD_STORE_PASSWORD
SYNC_CLIPBOARD_KEY_ALIAS
SYNC_CLIPBOARD_KEY_PASSWORD
```

缺少签名配置时仍可执行 Debug 构建。Release 构建会直接失败，避免生成来源不明确的发布包。

## Release 构建

配置签名后运行：

```bash
./gradlew assembleRelease
```

产物位置：

```text
app/build/outputs/apk/release/app-release.apk
system-extension/build/outputs/apk/release/system-extension-release.apk
```

发布前应确认两个 APK 的签名证书一致，并与已安装版本兼容。

## GitHub Actions

`.github/workflows/build.yml` 包含两个任务：

- `android`：在推送和 Pull Request 时执行单元测试、Lint 与 Debug 构建
- `release`：在 `android` 任务成功后，为手动触发或 `v*` 标签构建生产签名 APK

Release 任务使用以下 GitHub Actions Secrets：

```text
SYNC_CLIPBOARD_KEYSTORE_BASE64
SYNC_CLIPBOARD_STORE_PASSWORD
SYNC_CLIPBOARD_KEY_ALIAS
SYNC_CLIPBOARD_KEY_PASSWORD
```

其中 `SYNC_CLIPBOARD_KEYSTORE_BASE64` 保存 JKS 文件的 Base64 内容。工作流只在 Runner 临时目录恢复签名文件，不会将密钥写入仓库。

Release 构建完成后，`scripts/verify-release.sh` 会检查标签与版本号、两个 APK 的包名与版本、生产证书指纹及内置许可证，并生成 `SHA256SUMS`。生产证书的公开 SHA-256 指纹只维护在 `gradle/release-certificate.sha256`，私钥仍仅存在于本地和 GitHub Actions Secrets。

本地已有 Release APK 时，可在设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 后执行：

```bash
RELEASE_TAG=vX.Y.Z ./scripts/verify-release.sh
```

Windows 请使用 Git Bash，确保 `awk`、`grep`、`jar`、`java` 和 `sha256sum` 可用。校验工具缺失时脚本会停止，不会生成无效的 `SHA256SUMS`。

手动运行工作流会上传签名 APK 和 `SHA256SUMS` Artifact。推送 `v*` 标签还会创建对应的 GitHub Release。

## 模块版本

主体应用和系统扩展共用 `gradle.properties` 中的版本定义：

```properties
syncClipboard.versionCode=<递增整数>
syncClipboard.versionName=<版本号>
```

发布时只更新这一处，两个模块会生成相同版本的配套 APK。推送 `v*` 标签前，还应创建与标签同名的 `docs/release-notes/<tag>.md`。发布工作流会将该文件作为 GitHub Release 正文，缺失时停止发布。

发布说明要短，由 `scripts/verify_release_notes.py` 校验，工作流在发布前会跑一次：

- 正文不超过 40 行，首行不能是一级标题
- 必须包含 `## 主要更新`
- 必须链接到详细记录，链接指向 `blob/v<版本>/` 下的文档
- 不写 `## 使用说明`、`## 真机验收`、`## 构建与兼容范围` 和 `## 兼容边界`，这些内容属于 `docs/architecture.md`

本地可以先跑一次：

```bash
python3 scripts/verify_release_notes.py --tag vX.Y.Z
```

发布工作流还会在正文末尾追加 `## 构建信息`，列出标签与两个 APK 的 SHA-256。发布说明本身不要再写这一节。

Release 的标题就是标签本身，例如 `v0.2.0`，不添加产品名前缀。这样左侧的 Release 列表会直接显示版本号，而不是一列相同的产品名。
