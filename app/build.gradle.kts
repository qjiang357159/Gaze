import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.gaze.reader"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gaze.reader"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.7.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true

            // 签名信息不写进版本库：keystore.properties 已被 .gitignore 忽略，
            // 真正的 jks 放在工作区的 .secrets/ 下，不进项目目录。
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { stream -> props.load(stream) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // 没有签名信息时 release 仍可构建（产出未签名 APK），方便他人克隆后直接编译。
    val hasSigning = rootProject.file("keystore.properties").exists()

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.kotlin_module"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // 压缩格式解析：ZIP / TAR / 7Z
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // RAR 解析
    implementation("com.github.junrar:junrar:7.5.5")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
