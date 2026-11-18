plugins {
    id("com.android.application") version "8.9.3"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
    id("androidx.room") version "2.7.1"
}

val keyStorePath = providers.environmentVariable("BP_KEYSTORE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.bloodpressure.diary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bloodpressure.diary"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    if (keyStorePath != null) {
        signingConfigs {
            create("personal") {
                storeFile = rootProject.file(keyStorePath)
                storePassword = providers.environmentVariable("BP_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BP_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BP_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("personal")
    }

    buildTypes.getByName("debug").applicationIdSuffix = ".debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.compose = true
}

kotlin.jvmToolchain(17)
room.schemaDirectory("$projectDir/schemas")

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    testImplementation("junit:junit:4.13.2")
}
