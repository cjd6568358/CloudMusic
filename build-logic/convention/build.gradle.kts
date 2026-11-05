plugins { `kotlin-dsl` }

group = "com.example.cloudmusic.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 必须是 implementation（不能只 compileOnly）：precompiled script plugin 的
// generatePrecompiledScriptPluginAccessors 需要从 runtime classpath 解析
// `org.jetbrains.kotlin.jvm` 等插件 id 的 META-INF/gradle-plugins 描述符。
// 2026-11-05 首次编译实测：compileOnly 会报 "Plugin [id: ...] was not found"。
dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.gradlePlugin)
}

// 约定插件以 precompiled script plugin 形式提供：
//   src/main/kotlin/cloudmusic.jvm.library.gradle.kts
// `kotlin-dsl` 会自动把文件名注册为插件 id "cloudmusic.jvm.library"，
// 无需在 gradlePlugin {} 里手工声明 implementationClass。
// TODO(03-12 §1.1)：Android 模块落地时补 cloudmusic.android.library / .application 两个变体。
