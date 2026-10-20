plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.majidhajizade.messages"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.majidhajizade.messages"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.airbnb.android:lottie:6.6.7")
    implementation("com.google.android.gms:play-services-nearby:19.5.1")
}
