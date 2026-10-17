plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.julien_wff.aria_android.downloads"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(project(":aria2"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
