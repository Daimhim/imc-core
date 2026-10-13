// JitPack 专用最小 settings —— 只构建 :imc-core,不碰 :app / :weaknet。
//
// 背景(2026-10-13):JitPack 构建镜像从 default 换成 latest 后,1.1.17 构建 20 分钟
// 超时、0 条 `> Task :` 输出(gradle 卡在插件/依赖解析阶段)。根 build.gradle.kts 的
// plugins{} 块即使写了 `apply false` 也会去解析 AGP / Kotlin-Android / Compose,
// 而这些依赖要走 aliyun / maven.pkg.jetbrains.space —— 对 JitPack 新镜像不可达。
// 用 rootProject.buildFileName 把根项目的 build 脚本换成 jitpack.build.gradle.kts
// (空脚本),插件面只剩 kotlin-jvm + maven-publish(:imc-core 自己声明的)。
//
// 注意不能用 `gradle -b`:Gradle 7.6 会拒绝 "Build file is not part of the build"。
// 对比:1.1.16 在旧镜像 46s 成功;1.1.17 在新镜像超时失败(错误态已被 JitPack 缓存,
// 同名 tag 不可重建,故发 1.1.18 走本文件)。
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // 与 imc-core/build.gradle.kts 的 id("org.jetbrains.kotlin.jvm") 对应
        kotlin("jvm").version("1.8.0")
    }
}

rootProject.name = "imc-core-jitpack"
// 关键:换掉根项目的 build 脚本,别用 build.gradle.kts(它那组 plugins{} 会拉 AGP/Compose)
rootProject.buildFileName = "jitpack.build.gradle.kts"
include(":imc-core")
