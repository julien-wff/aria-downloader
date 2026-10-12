plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.julien_wff.aria_android.aria2"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
