plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("gradle/signing.gradle"))
apply(from = rootProject.file("gradle/license.gradle"))

android {
    namespace = "com.neko7ina.syncclipboard.extension"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neko7ina.syncclipboard.extension"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("syncClipboard.versionCode").get().toInt()
        versionName = providers.gradleProperty("syncClipboard.versionName").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":bridge-api"))
    compileOnly("io.github.libxposed:api:102.0.0")
}
