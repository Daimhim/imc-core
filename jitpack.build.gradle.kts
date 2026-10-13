// JitPack 专用空根 build 脚本(配合 jitpack.settings.gradle.kts 使用,
// 由 rootProject.buildFileName 指到这里,不是用 gradle -b)。
//
// 存在的意义:根项目的 build.gradle.kts 里那组 `id("com.android.application") apply false`
// 等插件声明,即使不 apply 也会触发 AGP/Compose 的解析下载 —— 在 JitPack 新镜像上
// 这一步会挂死(1.1.17 20 分钟超时的根因)。换成本脚本后,根项目不再声明任何插件,
// 构建面只剩 :imc-core 自己的 kotlin-jvm + maven-publish。
//
// 仓库也要在这里重声明:原来它们挂在根 build.gradle.kts 的 allprojects{} 上,
// 换掉脚本后就丢了。只留 JitPack 机房可达的两个 —— 刻意不含 aliyun / maven.pkg.jetbrains.space,
// 正是那些仓导致新镜像上解析挂死。
allprojects {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
