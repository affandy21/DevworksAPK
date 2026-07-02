import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

val uploadKeystorePropertiesFile = file(
    System.getenv("DEVWORKS_UPLOAD_KEYSTORE_PROPERTIES")
        ?: "${rootProject.projectDir}/keystore.properties"
)
val uploadKeystoreProperties = Properties().apply {
    if (uploadKeystorePropertiesFile.isFile) {
        uploadKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "co.id.devworks.attendance"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "co.id.devworks.attendance"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        create("release") {
            if (uploadKeystorePropertiesFile.isFile) {
                storeFile = file(uploadKeystoreProperties.getProperty("storeFile"))
                storePassword = uploadKeystoreProperties.getProperty("storePassword")
                keyAlias = uploadKeystoreProperties.getProperty("keyAlias")
                keyPassword = uploadKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (uploadKeystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.browser:browser:1.9.0")
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-messaging")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
