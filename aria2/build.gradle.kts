plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.julien_wff.aria_android.aria2"
    compileSdk { version = release(libs.versions.androidSdk.get().toInt()) }
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val nativeDependencies = tasks.register<Exec>("nativeDependencies") {
    description = "Build checksum-verified libaria2 and dependencies for both Android ABIs"
    val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
    inputs.files("native/build.py", "native/sources.json", "../gradle/libs.versions.toml")
    outputs.dir(layout.buildDirectory.dir("native/install"))
    commandLine("python3", "native/build.py", "--sdk", sdk.absolutePath)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(nativeDependencies)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
