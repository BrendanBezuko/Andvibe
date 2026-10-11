// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":app"))
    kover(project(":core"))
}

// Merged JVM unit-test coverage for :app (debug) and :core.
// ./gradlew koverHtmlReportUnit  → build/reports/kover/htmlUnit/index.html
kover {
    currentProject {
        createVariant("unit") {}
    }
    reports {
        filters {
            excludes {
                classes("*.BuildConfig", "*.R", "*.R$*", "*.databinding.*")
            }
        }
    }
}
