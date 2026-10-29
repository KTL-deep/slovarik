import java.security.MessageDigest

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
        versionCode = 4
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" }
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

// Never produce an APK that silently lacks the promised first-launch offline models.
val verifyBundledTranslation by tasks.registering {
    val assetRoot = layout.projectDirectory.dir("src/main/assets/translation")
    inputs.dir(assetRoot)
    val marker = layout.buildDirectory.file("intermediates/translation-models/verified.txt")
    outputs.file(marker)
    doLast {
        val expected = mapOf(
            "en-ru/encoder_model_quantized.onnx" to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
            "en-ru/decoder_model_quantized.onnx" to "7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574",
            "ru-en/encoder_model_quantized.onnx" to "c10df0466540534bb70c4402d539c349b8bfa9968184cad89f51408cf13e7eb7",
            "ru-en/decoder_model_quantized.onnx" to "d880abf58612013815554ef55c98d0f28b68190b2cb6f65c39d6e834f51dda8c"
        )
        expected.forEach { (path, hash) ->
            val file = assetRoot.file(path).asFile
            check(file.isFile) { "Missing $path. Run python tools/fetch_translation_models.py" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(1024 * 1024)
                var count = input.read(bytes)
                while (count >= 0) { digest.update(bytes, 0, count); count = input.read(bytes) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Translation model integrity check failed: $path" }
        }
        listOf("Apache-2.0.txt", "CC-BY-4.0.txt", "ONNX-Runtime-MIT.txt").forEach { name ->
            check(assetRoot.file("licenses/$name").asFile.isFile) { "Missing model license. Run python tools/fetch_translation_licenses.py" }
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("Verified pinned OPUS-MT assets and licenses") }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledTranslation) }
