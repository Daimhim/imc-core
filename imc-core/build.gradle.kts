plugins {
//    id 'java-library'
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

group = "com.github.Daimhim"
version = "1.1.16"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:4.9.0")
    compileOnly("com.github.Daimhim.timber-multiple-platforms:timber:1.0.8")
    compileOnly("org.java-websocket:Java-WebSocket:1.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.java-websocket:Java-WebSocket:1.5.4")
    // slf4j-simple 让 java-websocket 的内部日志不抛 NOP warning
    testImplementation("org.slf4j:slf4j-simple:1.7.36")
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed")
    }
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