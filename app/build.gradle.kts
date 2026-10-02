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
    packaging {
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jgit)
    implementation(libs.slf4j.nop)
    implementation(libs.rhino)
    implementation(libs.apksig)
    implementation(libs.spongycastle.core)
    implementation(libs.spongycastle.prov)
    implementation(libs.spongycastle.pkix)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
