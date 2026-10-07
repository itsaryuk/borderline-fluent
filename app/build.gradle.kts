plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.lampensoft.borderlinefluent"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.lampensoft.borderlinefluent"
        minSdk = 33
        targetSdk = 37
        // The release workflow passes -PversionCode / -PversionName derived from the tag (vX.Y.Z →
        // X*10000 + Y*100 + Z, "X.Y.Z"); every other build keeps the local defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Optional upload keystore from the environment. CI never has it: the release workflow builds an unsigned AAB
    // (stripping any signature) and the owner signs it locally with jarsigner. Without it a release build stays
    // debug-signed, which Play rejects, so nothing can ship by accident.
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE")
    signingConfigs {
        if (uploadKeystore != null) create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            // ponytail: no R8 yet. sherpa-onnx and ML Kit reach Kotlin classes from JNI or by reflection; turn
            // minify on only with keep rules proven by a release build on the phone.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Play Asset Delivery: one on-demand pack per language (officers, destinations) and one core pack (Whisper).
    assetPacks += listOf(":packs:lang_ar", ":packs:lang_de", ":packs:lang_en", ":packs:lang_es", ":packs:lang_fr", ":packs:lang_it", ":packs:lang_ja", ":packs:lang_pl", ":packs:lang_pt", ":packs:lang_ru", ":packs:lang_tr", ":packs:lang_uk", ":packs:lang_zh", ":packs:core_whisper")
    testOptions { unitTests.isReturnDefaultValues = true }
    // The interface language is switchable in-app (13 languages): ship every language in the base APK.
    // Split per language, a device only got its system languages (en, ru) and any other UI choice fell back to English.
    bundle { language { enableSplit = false } }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.google.android.play:asset-delivery:2.3.0")
    // The officer's eyes: front camera + bundled (offline) face detection.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Is the answer in the desk's language? Bundled model, on-device, no download (Nano's LANGUAGE flag misread names).
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // System Gemini Nano (AICore): the officer, hints and answer check; ML Kit speech per language.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
