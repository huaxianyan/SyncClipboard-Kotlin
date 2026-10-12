plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = rootProject.file("gradle/signing.gradle"))
apply(from = rootProject.file("gradle/license.gradle"))

android {
    namespace = "com.neko7ina.syncclipboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.neko7ina.syncclipboard"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("syncClipboard.versionCode").get().toInt()
        versionName = providers.gradleProperty("syncClipboard.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets.getByName("main").res.directories.add(rootProject.file("branding/res").path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":bridge-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.signalr:signalr:9.0.19")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.2")

    testImplementation("junit:junit:4.13.2")
}
