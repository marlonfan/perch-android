plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.perch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "app.perch"; minSdk = 26; targetSdk = 36; versionCode = 8; versionName = "0.5.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    val signingPath = providers.environmentVariable("PERCH_KEYSTORE_PATH").orNull
    signingConfigs {
        if (signingPath != null) create("release") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("PERCH_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("PERCH_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("PERCH_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (signingPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.atlassian.commonmark:commonmark:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
