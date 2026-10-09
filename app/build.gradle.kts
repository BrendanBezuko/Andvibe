plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.andvibe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.andvibe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        // Extract jniLibs to nativeLibraryDir so LD_PRELOAD (libjrehome.so) has a path.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

val bundleShellApk = tasks.register<Copy>("bundleShellApk") {
    dependsOn(":shell:assembleDebug")
    from(rootProject.file("shell/build/outputs/apk/debug"))
    include("*.apk")
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "shell.apk" }
}

tasks.configureEach {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn(bundleShellApk)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Kept only to one-time-migrate existing EncryptedSharedPreferences blobs.
    implementation(libs.androidx.security.crypto)
    implementation(libs.apksig)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.spongycastle.core)
    implementation(libs.spongycastle.prov)
    implementation(libs.spongycastle.pkix)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM tests (Android stubs return null with returnDefaultValues).
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
