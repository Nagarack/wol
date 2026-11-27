plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wolremote"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.wolremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Clé de signature fixe : les mises à jour s'installent par-dessus, sans désinstaller.
    signingConfigs {
        create("fixed") {
            storeFile = file("mon-pc.keystore")
            storePassword = "monpcstore"
            keyAlias = "monpc"
            keyPassword = "monpcstore"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
}
