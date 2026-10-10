plugins {
    id("com.android.application")
}

android {
    namespace = "com.commandopark.atak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.commandopark.atak.field"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
