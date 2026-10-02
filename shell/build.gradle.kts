plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.andvibe.built"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andvibe.built"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
