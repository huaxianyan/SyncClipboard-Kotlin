plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/signing.gradle"))
apply(from = rootProject.file("gradle/license.gradle"))

android {
    namespace = "com.neko7ina.syncclipboard.extension"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.neko7ina.syncclipboard.extension"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("syncClipboard.versionCode").get().toInt()
        versionName = providers.gradleProperty("syncClipboard.versionName").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets.getByName("main").res.directories.add(rootProject.file("branding/res").path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":bridge-api"))
    compileOnly("io.github.libxposed:api:102.0.0")
}
