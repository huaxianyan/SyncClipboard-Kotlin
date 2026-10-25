# 长期决策

只记稳定结论，被推翻时才改。当轮过程与试错放在同目录的日期文件里。

需要指向本机路径、设备信息、凭据位置或自建服务地址时，只写「见本地 `AGENTS.md`」，把具体值留在那个不入库的文件里。

## 仓库定位

- 主体仓库 `https://github.com/huaxianyan/SyncClipboard-Kotlin`。
- 系统扩展仓库 `https://github.com/huaxianyan/SyncClipboard-Kotlin-Extension`。扩展于 2026-10-13 从主体拆出，两个仓库各有独立的版本定义、标签与 Release，互不影响。
- 正式包名 `com.neko7ina.syncclipboard`（主体）、`com.neko7ina.syncclipboard.extension`（扩展）。
- 当前正式基线：主体 `v0.3.1`（versionCode 14），扩展 `v0.3.1`（versionCode 14）。target SDK 37（Android 17），min SDK 29（Android 10）。
- 项目派生自上游 `Jeric-X/SyncClipboard`（MIT），启动图标亦改自上游，署名与许可全文在 `LICENSES/`。
- 正式签名身份必须保持不变，老版本用户才能原地覆盖安装。私钥、口令与其他签名材料只保存在仓库外或 GitHub Actions Secrets 中。证书指纹见本地 `AGENTS.md`。
- 上游已实现「最后一次同步时间」：issue #427 于 2026-10-09 由提交 `d9f60d2e` 关闭（`feat: 为远端剪贴板添加带时区偏移的同步时间`），`ProfileDto` 新增 `syncedAt`，随 `v3.3.2` 与 `v3.3.3`（2026-10-18）发布。官方服务器在保存当前剪贴板时用服务器时间写入，并忽略客户端 PUT 提供的值，WebDAV 与 S3 由客户端自行设置。字段写进 `SyncClipboard.json`，`GET /SyncClipboard.json` 与 SignalR 推送都会带。
- `syncedAt` 语义是「这份远端内容最近一次提交的时刻」，同 hash 重复推送会刷新，与历史接口的 `createTime`（首见时刻，不随重复上传变化）不同。官方文档明确要求不要用它判断并发写入顺序。
- 自建服务器自 2026-10-18 起已运行带该字段的镜像，2026-10-25 核实数据文件里确有 `SyncedAt`。客户端已接入，判定主路径不再请求历史接口。

## 助手私有目录与交接

- 助手私有目录（`.workbuddy/`、`.workbuddy-ai/`、`.pi/`、`.claude/`、`.cursor/`）一律排除在 Git 之外，只放临时草稿。
- 进度与交接记录写在仓库的 `docs/handoff/` 下，跟代码一起提交。换任何工具都能读到，不为某个软件做特化。
- `docs/handoff/DECISIONS.md` 记长期决策，`docs/handoff/YYYY-MM-DD.md` 记当日流水。
- 因为进度文档入库，它不得写本机路径、内网地址、设备序列号、凭据位置或口令。需要指向这些信息时只写「见本地 `AGENTS.md`」。
- 本机相关的那份 `AGENTS.md` 不入库（`.git/info/exclude` 忽略），另存一份到私有归档仓库，供换设备后接手。
- 结论要写清验证强度与边界，实测与推断分开。推翻旧结论时指名作废并给出反证。

## 中文文案与排版

- 遵循 `chinese-tech-writing` 技能（阮一峰《中文技术文档的写作规范》加 AI 腔清单）。
- **引号保留直角引号 `「」`**，与当前中文技能一致。旧记录中的引号标准冲突已失效。
- 硬规则：逗号截 ≤ 30 汉字、整句 ≤ 100 汉字、中文与英文数字间留半角空格、全角标点、README 与发布说明禁用分号、尽量不用破折号。
- 代码、命令、路径、URL、资源键与必须保持字节不变的技术标识不作机械改写。
- **行尾不统一**：多数文件 CRLF（`core.autocrlf=true`），`docs/build.md`、`.gitignore`、`.github/workflows/build.yml` 是 LF。写多行匹配的补丁脚本要用 `io.open(..., newline="")` 读，按文件实际行尾换算模式里的 `\n`，否则多行匹配必失败。
- 批量改文字用「显式 (文件, 原文, 新文) 列表加逐条断言命中 1 次」的脚本，比逐条编辑可靠。

## 发布流程与说明格式

- 版本号唯一来源是 `gradle.properties`。推 `v*` 标签触发 `.github/workflows/build.yml` 的 `release` 任务：构建 → `scripts/verify-release.sh`（标签必须等于 `v$VERSION_NAME`，核对证书指纹与内置许可证，生成 `SHA256SUMS`）→ 校验发布说明 → 建 Release。
- **发布说明必须在 `docs/release-notes/v<版本>.md`**，缺失直接失败。门禁 `scripts/verify_release_notes.py` 按 `release-notes-standard`：不得有 H1、≤ 40 行、新功能与问题修复分成 `## 新功能` 与 `## 问题修复` 两节且至少出现其中一节、每个分类节至少一条 `- ` 条目、必须链接 `blob/v<版本>/…`，且不得出现 `## 使用说明`、`## 真机验收`、`## 构建与兼容范围`、`## 兼容边界`。回归测试 `scripts/test_verify_release_notes.py`（6 个用例，CI 的 android 任务会跑）。本地预检 `python3 scripts/verify_release_notes.py --tag vX.Y.Z`。
- **本项目把 `## 改进` 也当作合法分类节**：规范本身只认新功能与问题修复，但规范又说「用户可感知的其他改进可另设 `## 改进`」，否则只换图标这类版本只能硬塞成新功能。
- 工作流会在正文后追加 `## 构建信息`（标签加各 APK 的 SHA-256），说明文件里不要再写这一节。
- **Release 标题就是标签本身**（`v0.2.1`），不加产品名前缀。加前缀会让左侧 Release 列表变成一列被截断的同一个名字，看不出是哪个版本。
- 顺序是先推 master 再推标签，master 那次失败还有机会修，发布成功后 Release 不可撤销。
- 拆分前的历史 Release（主体 `v0.1.0`–`v0.3.0`）保留原有的扩展 APK 资产，不回删也不重传 `SHA256SUMS`。需要说明迁移时只改说明文字，用 `gh release edit --notes-file` 刷新，并保留工作流追加的 `## 构建信息`。主体 Release 此后只提供 `SyncClipboard-Kotlin-<版本号>.apk`，扩展的版本一律从扩展仓库获取。
- 复核产物：`gh release view` 比对资产大小，再经本地代理下载，用 `apksigner verify --print-certs` 与 `aapt2 dump badging` 核对。`gh release download` 直连极慢，别用。
- release 包走了 aapt2 crunch，PNG 被转成调色板 PNG 并重命名（`res/CH.png`），**不能按字节比对图标**，要按尺寸定位后逐像素核对，alpha 与可见像素一致即可。

## 版本号与发布产物命名

- 主体沿用 `syncClipboard.versionCode` 与 `versionName`，扩展仓库有自己的 `gradle.properties`。两者版本号不必相同，主体更新后可以继续用旧扩展，扩展只在自身有更新时才需要替换。
- 发布 APK 命名沿用「项目名-版本号.apk」：主体 `SyncClipboard-Kotlin-<版本号>.apk`，扩展 `SyncClipboard-Kotlin-Extension-<版本号>.apk`。无原生库、无 ABI 拆分，因此不带 ABI 段。校验文件仍用汇总 `SHA256SUMS`。
- AGP 9.1.1 已移除公开的 `VariantOutput.outputFileName`，改用根项目的 `distRelease`（`Sync` 类型）把 release APK 收集到 `build/dist`，只用公开 Gradle API。
- `distRelease` 必须带 `outputs.upToDateWhen { false }`。`Sync` 只在真正执行时才清理多余文件，而 up-to-date 检查发现不了输出目录里多出来的文件，不强制跑就会让旧版本 APK 残留，被 CI 的通配符一起上传。
- 发布流程是 `distRelease` → `scripts/verify-release.sh` 读 `build/dist` → 工作流上传 `build/dist/*.apk` 与 `build/release/SHA256SUMS`。模块构建目录下的 `*-release.apk` 只是中间产物。
- Obtainium 的 `apkFilterRegEx` 匹配 release 资产的文件名，且过滤前已按 `.apk`、`.xapk`、`.apkm`、`.apks` 扩展名筛过，`.sha256` 旁挂文件不参与过滤。两个入口的正则必须互斥，否则 Obtainium 会再按设备 ABI 筛一轮，文件名里没有 ABI 字样时筛不掉，会弹窗让用户手选。

## 构建工具链与目标 SDK

- 目标 SDK 37（Android 17），最低支持 Android 10（API 29）。
- 工具链固定 AGP 9.1.1、Gradle 9.3.1、KGP 2.2.10。AGP 9 起内置 Kotlin，各模块都不再应用 `org.jetbrains.kotlin.android`，`kotlinOptions` 已移除，JVM 目标只由 `compileOptions` 决定。
- AGP 9 的 SourceSet API 不再接受 `Provider`。资源目录改用 `directories` 可变集合（`:app` 挂 `branding/res`），生成的 LICENSE assets 也用 `directories.add(...)` 并保留 `preBuild dependsOn`。
- 优先使用仓库 Wrapper。2026-10-13 历史验证确认 `./gradlew` 可用，旧限制已作废。需要绕开 shell 入口时，使用 `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain` 等效调用，不另装 Gradle。
- 完整校验是 `testDebugUnitTest`、`lintDebug`、`assembleRelease` 三项。
- 构建走本地 HTTP 代理时给 Gradle 进程加代理参数，否则下载发行包会 `Connection reset`。具体值见本地 `AGENTS.md`。

## Android 17 局域网权限

- targetSdk 37 起，访问局域网必须持有 `ACCESS_LOCAL_NETWORK`（属「附近设备」权限组）。限制在网络栈实现，OkHttp 与 SignalR 一并受管，公网与移动网络不受影响。
- 统一入口 `net/LocalNetworkAccess.kt`：`isGranted`、`needsPermission`（按 host 判断，含 `.local` 与 DNS 解析结果）、`configure`（自定义 `Dns` 加应用拦截器加网络拦截器逐跳检查重定向）。
- 权限属于主体应用 UID，扩展无需声明。`SyncFailureKind.PERMISSION` 贯穿事件存储、桥接常量（`AUTOMATIC_SYNC_ERROR_PERMISSION = 9`）与界面文案。
- 只在用户发起操作时请求：首页「重新检查」、设置里的「测试连接」、磁贴。主界面首次读取配置时静默检查，不弹窗。

## 暂停恢复判定

设置项 `receive_paused_remote_changes=false` 表示「不接收暂停期间的云端更新」。

- **主判据是暂停窗口**（`PauseWindowPolicy`）：`syncedAt` 早于「本轮恢复同步的时刻」超 1 秒（`PAUSE_WINDOW_TOLERANCE_MILLIS`）就归档进文本历史、不写剪贴板。恢复时刻取自动同步循环从停止态重启的瞬间，**不需枚举停摆原因**。判据只比两个时刻，补查延迟不再参与比较，所以容差比旧的 5 秒紧得多。
- **推送与补查用同一套判定**。旧实现排除推送，是因为 `createTime` 在同 hash 重复提交时不刷新，重新提交旧内容与积压内容无法区分。`syncedAt` 每次提交都刷新，这个理由不再成立。
- **降级判据是 `createTime`**（`StaleRemoteContentPolicy`）：服务端没有 `syncedAt` 时，补查退回历史接口的 `createTime` 与「现在」比较，宽限 5 秒（`STALE_CONTENT_GRACE_MILLIS`）。这条口径仍分不清重新提交与积压，所以**只用于补查，推送不判**。
- **最后的降级是哈希基线**（`RemoteSyncResumePolicy`），两条时间判据都拿不到时启用（老服务端、纯 WebDAV、时钟偏移超限）。`needsBaselineSuppression` 等于选项开着且 `remoteClockOffsetMillis == null`。
- 历史教训（0.1.8 到 0.1.9 反复出问题）：根因是判定依赖「枚举停摆原因」。`shouldRunRemoteSync()` 每条存活条件（`enabled`、`downloadsEnabled`、`deviceUnlocked`、`networkAvailable`、`systemBridge`）丢失都要上报 `onAutomaticConditionLost()`，`systemBridge` 是历史漏报的那条。
- `remoteClockOffsetMillis` 曾只在 `establishRemoteBaseline()` 里赋值，于是「不建基线 → 偏移 null → 时间判据全废」。现由 `calibrateServerClock()` 在循环启动时独立校准，与基线彻底解耦。
- 该选项开启时，「锁屏期间在电脑上复制 → 解锁手机粘贴」这条路径会被拦。这是选项语义本身而非缺陷。接 `syncedAt` 之后，误判窗口只剩「解锁前 1 秒内复制」这一种可能，比旧的 5 秒宽限口径更窄。
- 恢复时刻只在循环从停止态重启时记录，设置变更触发的重启不重置窗口，否则短暂重启会把刚推来的内容误判成积压。

## 内容时间的服务端语义

- **hash 只是内容的函数**：文本是 `SHA-256(trim 后的 UTF-8)`。A → B → C → 再复制 A，两个 A 的 hash 逐字节相同，服务端命中已有记录只 `version++`。图片与文件是 `sha256("文件名|内容SHA256")`。
- `createTime` 是**首次**到达云端时刻，**永不刷新**，服务端三条写路径都不碰它。`lastModified` 不可用，PATCH 标星与置顶也会刷它，`UpdateEntityFields` 把客户端送来的时间原样写回。`version` 也不是上传次数。
- `syncedAt` 相反，**每次提交都刷新**，`v3.3.2` 起随 `SyncClipboard.json` 与 SignalR 推送下发，语义是「最近一次提交时刻」。判断「恢复同步之前还是之后」用它，判断「这份内容多久以前第一次出现」才用 `createTime`。
- 本仓上传走 legacy `PUT /SyncClipboard.json`，不上送 `syncedAt`。官方服务器收到后会用服务器时间补上，客户端送的值也会被忽略，因此不需要我们写。WebDAV 与 S3 没有服务端补值，只有上游新版客户端上传时才带。
- 带时间的接口是 `GET /api/time`、`GET /api/history/{Type}-{Hash}`、`POST /api/history/query`。`{profileId}` 是 **`Type-Hash` 单段**，写成 `Text/<hash>` 会落到 PATCH 路由返 405。**`/api/current` 不存在**（实测 404，别写进代码）。老部署与纯 WebDAV 上 history 系列 404，探测式处理，拿不到就降级。
- 上游 issue #427 的接入点：`ClipboardPayload.syncedAtMillis` 由 `SyncTimestampParser.parseSyncedAt()` 提取，判定在 `PauseWindowPolicy`，`getRemoteContentTimestamp()` 降为老服务端的兜底。

## 双通道

- **推送是主通道**：`SignalRSyncClient` 只订阅 `RemoteProfileChanged`。**每次 PUT 都推，hash 命中已有记录也推**（`SaveAndNotifyCurrentProfile` → `Clients.All`，无条件）。
- **补查只在两处**：一是 `client.start()` 后立刻一次，二是实时通道失败时每 5 分钟兜一次。**SignalR 不重放离线消息**。

## 下载去重与跨进程偏好文件

- 旧实现 `applyRemoteAutomatically()` 首行 `sourceHash == previousHash` 即 return，而该字段被上传、下载、归档六处共用，只表达「这个 hash 我处理过了」。修法是新增 `local_clipboard_hash`，三处写入（本地复制上报 `onClipboardText`、`writeClipboardText()` 成功、延迟写入重试成功）。文本抑制基准改为**本机剪贴板事实**，拿不到就**不抑制**，因为写入总是安全的，误抑制才让人拿不到内容。登记值只在同一次开机内可信（`LocalClipboardState.usableHash` 用 `SystemClock.elapsedRealtime` 判）。抑制命中记 `REMOTE_DEDUPED`。图片与文件没有可读回的本机状态，始终用 `previousHash`。
- **顺序约束**：补查里**先判「本机已有」再判陈旧**。`staleRemoteContentAgeMillis()` 里删掉了 `sourceHash == previousHash` 豁免，顺序颠倒会让正常内容在每次重连时都被判陈旧归档。
- 文本哈希必须同源，用 `PayloadFactory.textHash(text)`。
- **跨进程覆盖**：`SystemBridgeService` 跑在 `android:process=":sync"`，两个进程各持 `SharedPreferences` 内存副本（`MODE_PRIVATE` 不跨进程同步），任一方写入都会用自己内存里的整张表重写文件，抹掉对方刚写的键。**铁律是每个偏好文件只能有一个写入者**：
  - `sync_clipboard_settings.xml` 只由主进程写（设置、服务器配置、手动同步时间）。
  - `sync_clipboard_runtime.xml` 只由 `:sync` 写（`local_clipboard_hash` 与其 `_elapsed_millis`、`last_automatic_remote_hash`、`pending_automatic_text`、自动同步时间与方向）。
  - **只读方**用 `MODE_MULTI_PROCESS` 取实例（按文件时间戳重读），**写入方**仍 `MODE_PRIVATE`。
  - 服务器配置变更靠设置文件里的 `server_config_token`（UUID）让旧同步点失效，不再跨文件清键。
  - **`ClipboardTransferService` 原本自带 `SettingsRepository(context)`，却在 `:sync` 里被 new 了 5 处**，是最容易漏的一处，已改构造器注入。查「谁在写哪个文件」要把这类对象也算进去。
  - 设计说明在 `docs/architecture.md` 的「进程与偏好文件」一节。

## 自动同步事件表

格式 `时间戳|事件|失败分类|内容类型|内容年龄ms`，解码器按 `size < 4` 才拒绝。最多 100 条、保留 7 天，除上传与下载成功外，与上一条同状态的事件**直接丢弃**，扩展连断事件异步落盘、顺序无保证。

- `download_succeeded` **不带** `contentType` 表示来自 `pollRemoteClipboard()`，**带** `Text` 表示来自推送或写入重试。`remote_deduped` 表示远端来了但登记值已等于它。另有 `baseline_established` 与 `stale_write_skipped`（带内容年龄）。
- 归档前提是 `TextSyncHistory.record(enabled = settings.textHistoryEnabled)`，没开文本历史时「归档」等于丢弃。

## 页面导航与转场

- 页面模型单一定义点 `ui/PageNavigation.kt`（`AppPage` 的 `depth` 与 `order`、`parentPage`、`isForwardFrom`），转场参数单一定义点 `ui/PageMotion.kt`。新增页面只改枚举，不再改任何转场判断。
- 转场参数取自样板应用 PixelText 的 `AppNavigation.kt`。普通点击切页、顶部返回按钮、预测性返回手势共用一套，手势进度即转场进度。
- 返回时 `AnimatedContent` 的 `targetZIndex` 必须为负（本项目 ±1f）。zIndex 只有目标内容能带，且相等时后加入者在上，给正值会让当前页向外退出时被上一页盖住。
- 中段可读性靠参数本身保证：新页淡入 90 ms 早于旧页 210 ms 的淡出延迟，手势停在中段两页都不透明。不要给手势路径加透明度。
- 手势用 `PageGesturePhase`（IDLE、DRAGGING、SETTLING）配 `SeekableTransitionState`，`currentPage` 只在收尾动画结束后提交，取消时只退回 IDLE 并把恢复交给 IDLE 分支。
- 清单需 `android:enableOnBackInvokedCallback="true"` 并配 `tools:targetApi="33"`，否则 lint 报 `UnusedAttribute`。`scripts/verify_motion.py` 守护该声明、targetSdk ≥ 36 与三处接入点唯一，拒绝 `BackHandler`、`onBackPressed`、`KEYCODE_BACK`。

## 导航栏内容侵入

页面切换是 `Scaffold` 加 `AnimatedContent`，底栏是 Scaffold 的 `bottomBar` 槽。

- **真因**：`enableEdgeToEdge()` 下 `Scaffold` 的 `contentPadding.bottom` 在底栏存在时等于**底栏当前高度**，而不是「底栏高度与导航栏 inset 取大者」。底栏退场把高度收到 0，页面容器底部内边距跟着到 0，内容铺到屏幕最底部，在导航栏 inset 那一条里露出正文。底栏彻底消失后内边距改由 `contentWindowInsets` 决定，内容被推回，于是出现「露一下，然后瞬间填充」。
- **修法两层**：一是 `PageColumn` 加 `bottomShortfall = (导航栏 inset − contentPadding 底边).coerceAtLeast(0)`，二是在 `Scaffold` 内容槽里、页面之上底栏之下盖一条与背景同色的条（高度等于导航栏 inset）。两层都实测不留缝。
- 判据是 crop 导航栏带里**页面正文**的像素数，须分辨 x 区间，因为底栏项与正文重叠在同一带里。改前进入方向峰值 223，改后 78 与 79 帧全为 0。
- 底栏自身的进出曲线是另一件事：退场 `tween(420, FastOutLinearInEasing)`、入场 `tween(300, LinearOutSlowInEasing)`，页面转场 `tween(300, FastOutSlowInEasing)`。`LinearOutSlowInEasing` 是「前段冲刺加末段刹车」，约 60% 位移压在开头 20% 里，而底栏能被看见的只有顶边扫过图标那一段，用它会短到看不出动画。

## 图标与品牌资源

- 启动图标在 **`branding/res`**，两个仓库各存一份副本，一处改一个包生效。现为绿色版云朵同步图标：底色 `#5DC092`，前景按 72 dp 安全区映射，内容最大半径 27.1 dp。
- 图标改自上游 `Jeric-X/SyncClipboard` 的图标，MIT 许可全文在 `LICENSES/MIT-SyncClipboard.txt`，README 的「参考项目」「许可证」两节各有指引。
- 经验：设计稿的圆角方块按 72 比 108（约 66.7%）画时，直接把方块对齐 72 dp 安全区即可，内容视觉分量不变。只取方块内部像素还原前景 alpha（先腐蚀 10 px），可避开方块边缘那圈「不透明白色混合」的抗锯齿白晕。映射规则写在 `docs/build.md` 的「品牌资源」一节。

## 已决定不改的行为

- 上游 issue #427 接口落地前不动客户端。
- 同 hash 重复上传不刷 `createTime`（服务端数据模型所致），所以「PC 端把一条旧内容重新推上来」在补查路径会被归档进历史而非写剪贴板，客户端绕不过。
- 不枚举停摆原因，只判内容时间。新增存活条件只要上报 `onAutomaticConditionLost()` 即可，不需要改判定逻辑。

## 待决

- 上游 issue #427 接口，等官方落地再补。
- 主体 `v0.3.0` 与扩展 `v0.3.1` 发布后，扩展的真机安装与自动同步未复测，设备连接当时不可用。
- 已用旧 Obtainium 入口导入过扩展的用户需改用新入口，用户称当前应无此类用户。
