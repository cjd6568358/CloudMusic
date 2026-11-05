// CloudMusic · 根构建脚本
// 模块总表见 docs/03-模块开发文档/03-12 构建发布与合规.md §1.1
// 版本唯一真相源：gradle/libs.versions.toml
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// C1~C6 串联入口（docs/03-12 §3）。脚本为零第三方依赖的纯 Python，
// 本机（无 Gradle/AGP/SDK）也可直接运行：python tools/pre_release_check.py
tasks.register<Exec>("preReleaseCheck") {
    group = "verification"
    description = "串联 C1~C6 静态检查（tools/pre_release_check.py），失败即阻断 release"
    workingDir = rootProject.projectDir
    val python = providers.gradleProperty("cloudmusic.python").orElse("python").get()
    commandLine(python, "tools/pre_release_check.py")
}

tasks.register("printVersionCatalog") {
    group = "help"
    description = "打印已钉死的构建版本，便于打包机核对"
    doLast {
        listOf(
            "gradle" to libs.versions.gradle.get(),
            "agp" to libs.versions.agp.get(),
            "kotlin" to libs.versions.kotlin.get(),
            "ksp" to libs.versions.ksp.get(),
            "compileSdk" to libs.versions.compileSdk.get(),
            "minSdk" to libs.versions.minSdk.get(),
            "targetSdk" to libs.versions.targetSdk.get(),
        ).forEach { (k, v) -> println("$k = $v") }
    }
}
