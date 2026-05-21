plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.daimhim.imc_core.weaknet"
    compileSdkVersion(33)
    defaultConfig {
        applicationId = "org.daimhim.imc_core.weaknet"
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
    // Android 基础
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("com.google.android.material:material:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.3")
    implementation("androidx.activity:activity-ktx:1.7.2")

    // TcpChaosProxy 内部日志走 Timber
    implementation("com.github.Daimhim.timber-multiple-platforms:timber-android:1.0.8")

    testImplementation("junit:junit:4.13.2")
}
