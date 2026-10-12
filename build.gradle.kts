plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// 发布产物命名沿用谷歌拼音仓库的规则：`项目名-版本号.apk`。
// 两个模块都不含原生库，也没有 ABI 拆分，只产出通用 APK，因此不保留 ABI 段。
// 系统扩展与主体同处一个仓库，靠 `Extension` 段区分，供 Obtainium 用文件名正则分别筛选。
val releaseVersionName = providers.gradleProperty("syncClipboard.versionName").get()

val releaseApkNames = mapOf(
    ":app" to "SyncClipboard-Kotlin-$releaseVersionName.apk",
    ":system-extension" to "SyncClipboard-Kotlin-Extension-$releaseVersionName.apk",
)

// 用 Sync 而不是 Copy：文件名带版本号，换版本后旧产物必须被清掉，
// 否则 build/dist 会同时留下多个版本，上传时按通配符取文件就会混入旧包。
// Sync 只在任务真正执行时才清理多余文件，而 Gradle 的 up-to-date 检查只比对
// 已声明的输出文件，发现不了目录里多出来的文件，所以这里强制每次都跑，
// 保证 build/dist 的内容严格等于当前版本的产物。
val distRelease by tasks.registering(Sync::class) {
    group = "build"
    description = "构建两个 release APK，按发布规则重命名后收集到 build/dist"
    dependsOn(releaseApkNames.keys.map { "$it:assembleRelease" })
    into(layout.buildDirectory.dir("dist"))
    outputs.upToDateWhen { false }
    releaseApkNames.forEach { (modulePath, apkName) ->
        val moduleName = modulePath.removePrefix(":")
        from(project(modulePath).layout.buildDirectory.file("outputs/apk/release/$moduleName-release.apk")) {
            rename { apkName }
        }
    }
}
