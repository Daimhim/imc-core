plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
}

android {
    namespace = "org.daimhim.imc_core.demo"
    compileSdkVersion(33)
    defaultConfig {
        applicationId  = "org.daimhim.imc_core.demo"
        minSdkVersion(22)
        targetSdkVersion(30)
        versionCode = 1
        versionName = "1.0"

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.github.Daimhim:ContextHelper:1.0.3")
    implementation("androidx.compose.runtime:runtime:1.0.0")
    implementation ("androidx.fragment:fragment-ktx:1.5.7")
    implementation ("androidx.activity:activity-ktx:1.7.2")
    implementation(project(":imc-core"))
    implementation("com.github.Daimhim:SimpleAdapter:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation("com.github.Daimhim.timber-multiple-platforms:timber-android:1.0.8")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("com.google.android.material:material:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
    implementation("com.github.kongqw:NetworkMonitor:1.2.0")
//    implementation("com.tencent.mars:mars-wrapper:1.2.5")
    implementation("com.tencent.mars:mars-core:1.2.5")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
    implementation("org.chromium.net:cronet-api:108.5359.79")
    testImplementation("com.github.Daimhim.timber-multiple-platforms:timber-desktop:1.0.8")
}