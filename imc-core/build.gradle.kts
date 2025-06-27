plugins {
//    id 'java-library'
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

group = "com.github.Daimhim"
version = "1.1.8"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:4.9.0")
    compileOnly("com.github.Daimhim.timber-multiple-platforms:timber:1.0.8")
    compileOnly("org.java-websocket:Java-WebSocket:1.5.4")
}

afterEvaluate{
    publishing {
        publications {
            create<MavenPublication>("releaseJar") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
                from(components["java"])
            }
        }
        repositories {
            maven {
                url = uri("../repo")
            }
        }
    }
}