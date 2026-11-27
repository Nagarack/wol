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
        versionCode = 13
        versionName = "3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Clé de signature fixe (fournie par les « secrets » GitHub, jamais dans le dépôt) :
    // les mises à jour s'installent par-dessus, sans désinstaller.
    val ksFile = file("mon-pc.keystore")
    signingConfigs {
        if (ksFile.exists()) {
            create("fixed") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "monpc"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
    }
}
