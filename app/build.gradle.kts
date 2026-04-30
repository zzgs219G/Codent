import java.util.Properties
import java.io.FileInputStream


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)         // 这里对应 TOML 里的新别名
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// 1. 签名配置（Codent 自动加载项目 release.properties）
val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()
var hasValidSigning = false

if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    hasValidSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        keystoreProps.containsKey(it)
    }
}

android {
    namespace = "com.xixin.codent"
    compileSdk = libs.versions.compileSdk.get().toInt()

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.xixin.codent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasValidSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasValidSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 开启 Compose 强跳过模式，这对移动端本地编译的 App 性能提升巨大
        freeCompilerArgs += listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:strongSkipping=true"
        )
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/kotlinx_coroutines_core.version"

            // 核心：解决 AndroidIDE 本地 Maven 依赖冗余冲突
            pickFirsts += "**/default/linkdata/**"
            pickFirsts += "**/root_package/0_.knm"
            pickFirsts += "**/package_androidx/0_androidx.knm"
            pickFirsts += "META-INF/kotlin-project-structure-metadata.json"
            
            merges += "**/default/manifest"
        }
    }
}

dependencies {

implementation("androidx.documentfile:documentfile:1.0.1")
    // UI 层 (Compose 核心 Bundle)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    // Agent 核心：网络（Ktor）与 序列化
    implementation(libs.bundles.ktor.full)
    implementation(libs.kotlinx.serialization.json)

    // Agent 核心：文件操作与 Diff 算法
    implementation(libs.okio)
    implementation(libs.java.diff.utils)

    // 图片加载与异步
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.slf4j.nop)

    // 调试预览
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
