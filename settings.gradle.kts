pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application").version(extra["agp.version"] as String)
        id("com.android.library").version(extra["agp.version"] as String)
        id("org.jetbrains.kotlin.android").version(extra["kotlin.version"] as String)
    }
}
rootProject.name = "imc-core"
//include(":app")
include(":imc-core")
