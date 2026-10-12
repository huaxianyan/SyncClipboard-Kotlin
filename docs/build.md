# 构建与发布

## 环境要求

- JDK 17
- Android SDK 37（API 37）
- Android 10（API 29）或更高版本的测试设备

构建工具链固定为 AGP 9.1.1 与 Gradle 9.3.1。AGP 9 起内置 Kotlin 支持，模块不再单独应用 `org.jetbrains.kotlin.android`，JVM 目标版本由 `compileOptions` 统一决定。

项目使用 Gradle Wrapper，不需要单独安装 Gradle。首次下载发行包走网络，需要代理时给 Gradle 进程加上 `-Dhttps.proxyHost` 与 `-Dhttps.proxyPort`。

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
./gradlew distRelease
```

`distRelease` 先构建两个模块的 release APK，再按发布规则重命名并收集到 `build/dist`：

```text
build/dist/SyncClipboard-Kotlin-<版本号>.apk
build/dist/SyncClipboard-Kotlin-Extension-<版本号>.apk
```

命名沿用谷歌拼音仓库的规则，即「项目名-版本号.apk」。两个模块都不含原生库，也没有 ABI 拆分，只产出通用 APK，因此不保留 ABI 段。系统扩展与主体同处一个仓库，靠 `Extension` 段区分，Obtainium 才能用文件名正则分别筛选。版本号取自 `gradle.properties` 的 `syncClipboard.versionName`。

收集任务用 Gradle 的 `Sync` 而不是 `Copy`。文件名带版本号，换版本后旧产物必须被清掉，否则 `build/dist` 会同时留下多个版本。`Sync` 只在任务真正执行时才清理多余文件。Gradle 的 up-to-date 检查只比对已声明的输出文件，发现不了目录里多出来的文件。因此该任务强制每次都跑，保证 `build/dist` 的内容严格等于当前版本的产物。

模块构建目录下的 `app-release.apk` 与 `system-extension-release.apk` 是未经重命名的中间产物，不要直接分发。只跑 `./gradlew assembleRelease` 不会生成发布命名的文件。

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

Release 构建完成后，`scripts/verify-release.sh` 会读取 `build/dist` 下的两个发布产物，检查标签与版本号、两个 APK 的包名与版本、生产证书指纹及内置许可证，并生成 `SHA256SUMS`。生产证书的公开 SHA-256 指纹只维护在 `gradle/release-certificate.sha256`，私钥仍仅存在于本地和 GitHub Actions Secrets。

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
- 新功能与问题修复分成 `## 新功能` 与 `## 问题修复` 两节，至少出现其中一节，每节至少一条 `- ` 条目
- 用户可感知的其他改进另设 `## 改进`（例如更换图标），本项目同样把它当作合法分类节
- 必须链接到详细记录，链接指向 `blob/v<版本>/` 下的文档
- 不写 `## 使用说明`、`## 真机验收`、`## 构建与兼容范围` 和 `## 兼容边界`，这些内容属于 `docs/architecture.md`
- `## 版本信息` 与 `## 构建信息` 至多出现一个，后者由工作流追加

本地可以先跑一次，并跑一遍门禁自身的回归测试：

```bash
python3 scripts/verify_release_notes.py --tag vX.Y.Z
python3 scripts/test_verify_release_notes.py
```

发布工作流还会在正文末尾追加 `## 构建信息`，列出标签与两个 APK 的 SHA-256。发布说明本身不要再写这一节。

Release 的标题就是标签本身，例如 `v0.2.1`，不添加产品名前缀。这样左侧的 Release 列表会直接显示版本号，而不是一列相同的产品名。

## 品牌资源

启动图标放在 `branding/res`。`:app` 与 `:system-extension` 都通过 `res.directories` 挂载这一份目录，改一处两个包同时生效，但构建要跑两个模块才会同时更新产物。

```text
branding/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png  前景位图
branding/res/drawable/ic_launcher_background.xml                       底色矢量，引用下面的颜色
branding/res/values/colors.xml                                         launcher_background
branding/res/mipmap-anydpi-v26/ic_launcher.xml                         自适应图标
branding/res/mipmap-anydpi-v33/ic_launcher.xml                         自适应图标与 Android 13 单色图标
```

设计稿按自适应图标规范绘制（圆角方块占画布约 66.7%）时，把设计稿的方块对齐到 **72 dp 安全区**，只导出图形部分作为透明前景，底色取设计稿自己的颜色。这样内容最大半径落在 33 dp 安全圈以内，换图前后桌面上的视觉分量保持一致。

只取方块内部的像素还原前景透明度。设计稿方块边缘的抗锯齿常常是「不透明白色混合」而不是真正的半透明，直接抠图会在圆角处留一圈白晕。

启动图标修改自上游 [Jeric-X/SyncClipboard](https://github.com/Jeric-X/SyncClipboard) 的图标，版权与许可声明见 `LICENSES/MIT-SyncClipboard.txt`。
