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
        targetSdkVersion(33)
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
    implementation ("androidx.fragment:fragment-ktx:1.5.7")
    implementation ("androidx.activity:activity-ktx:1.7.2")
    implementation(project(":imc-core"))
    implementation("com.github.Daimhim:SimpleAdapter:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("com.github.Daimhim.timber-multiple-platforms:timber-desktop:1.0.5")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("com.google.android.material:material:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
    implementation("com.github.kongqw:NetworkMonitor:1.2.0")
}