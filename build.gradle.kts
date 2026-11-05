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
// 不依赖 Android SDK，任一成员均可直接运行：python tools/pre_release_check.py
tasks.register<Exec>("preReleaseCheck") {
    group = "verification"
    description = "串联 C1~C6 静态检查（tools/pre_release_check.py），失败即阻断 release"
    workingDir = rootProject.projectDir
    val python = providers.gradleProperty("cloudmusic.python").orElse("python").get()
    commandLine(python, "tools/pre_release_check.py")
    // 统一子进程输出编码。Windows 中文终端经 Gradle 转发仍可能显示为乱码，
    // 直接运行 python tools/pre_release_check.py 输出正常，不影响检查结果与退出码。
    environment("PYTHONIOENCODING", "utf-8")
}

// 多人协作：一键自检本机 SDK 解析链，并守护「仓库零硬编码 SDK 路径」。
// 与 preReleaseCheck 一样不依赖 Android SDK 是否可编译，新成员接入第一步先跑它。
tasks.register<Exec>("androidEnvCheck") {
    group = "verification"
    description = "解析本机 Android SDK 并守护仓库内无个人 SDK 路径（tools/check-android-env.py）"
    workingDir = rootProject.projectDir
    val python = providers.gradleProperty("cloudmusic.python").orElse("python").get()
    commandLine(python, "tools/check-android-env.py")
    environment("PYTHONIOENCODING", "utf-8")
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
