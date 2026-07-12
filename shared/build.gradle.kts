plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "org.hubik.openfugu.shared"
        compileSdk = 36
        minSdk = 35

        // JVM unit tests (source set: androidHostTest)
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            // api: the app module builds its screens on these
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            api(libs.kotlinx.coroutines.core)
            api(libs.kable.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}
