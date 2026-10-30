plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A fresh checkout remains buildable/offline until the owner configures Firebase.
if (file("google-services.json").isFile) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.slovarik.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.slovarik.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // Keep the installed app's signature for this privately distributed build.
            // Store publication requires a separate owner-managed release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    androidResources { ignoreAssetsPattern += ":en-ru:ru-en" }
    // Both UI languages must remain available offline, including in future app bundles.
    bundle { language { enableSplit = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Model weights live in the source tree for development, not in the APK.
val verifyTranslationMetadata by tasks.registering {
    val assetRoot = layout.projectDirectory.dir("src/main/assets/translation")
    inputs.dir(assetRoot)
    val marker = layout.buildDirectory.file("intermediates/translation-models/verified.txt")
    outputs.file(marker)
    doLast {
        check(assetRoot.file("manifest.json").asFile.isFile) { "Missing translation manifest" }
        check(assetRoot.file("NOTICE.txt").asFile.isFile) { "Missing translation notice" }
        listOf("Apache-2.0.txt", "CC-BY-4.0.txt", "ONNX-Runtime-MIT.txt").forEach { name ->
            check(assetRoot.file("licenses/$name").asFile.isFile) { "Missing model license. Run python tools/fetch_translation_licenses.py" }
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("Verified translation metadata and licenses") }
    }
}
tasks.named("preBuild") { dependsOn(verifyTranslationMetadata) }
