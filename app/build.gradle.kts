plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val releaseVersionName = providers.gradleProperty("releaseVersionName")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
require(releaseVersionName.isPresent == releaseVersionCode.isPresent) {
    "Supply releaseVersionName and releaseVersionCode together"
}
val signingVariables = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
)
val signingValues = signingVariables.associateWith { providers.environmentVariable(it).orNull }
val missingSigningVariables = signingVariables.filter { signingValues[it].isNullOrBlank() }

android {
    namespace = "com.julien_wff.aria_android"
    ndkVersion = libs.versions.ndk.get()
    buildToolsVersion = libs.versions.buildTools.get()
    compileSdk {
        version = release(libs.versions.androidSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "com.julien_wff.aria_android"
        minSdk = 35
        targetSdk = libs.versions.androidSdk.get().toInt()
        versionCode = releaseVersionCode.orNull?.toInt()?.also {
            require(it in 1..2_100_000_000) { "releaseVersionCode is outside Android's supported range" }
        } ?: 1
        versionName = releaseVersionName.orNull ?: "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "com.julien_wff.aria_android.HostRecovery"
    }

    signingConfigs {
        if (missingSigningVariables.isEmpty()) {
            create("githubRelease") {
                storeFile = file(signingValues.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = signingValues.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("ANDROID_KEY_ALIAS")
                keyPassword = signingValues.getValue("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("githubRelease")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(missingSigningVariables.isEmpty()) {
            "Release signing requires: ${missingSigningVariables.joinToString()}. See docs/releases.md"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(project(":downloads"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
