// 项目根目录的 build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    // 关键修改：把 jetbrains.kotlin.android 改为 kotlin.android
    alias(libs.plugins.kotlin.android) apply false 
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}


tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
