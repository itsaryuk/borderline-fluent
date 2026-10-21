plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Game languages, one asset pack each (packs/lang_xx); heldBackLanguages (gradle.properties) is the release switch.
val gameLanguages = listOf("ar", "de", "en", "es", "fr", "it", "ja", "pl", "pt", "ru", "tr", "uk", "zh")
val heldBackLanguages = providers.gradleProperty("heldBackLanguages").orNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    .onEach { require(it in gameLanguages) { "heldBackLanguages: '$it' is not a game language ($gameLanguages)" } }
val shippedLanguages = gameLanguages - heldBackLanguages.toSet()
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
        // Where "report this suggestion" sends its anonymous web report: BF_REPORT_URL, else bfReportUrl from
        // ~/.gradle/gradle.properties. Unset (the open-source build), the dialog only copies the report.
        val reportUrl = (providers.environmentVariable("BF_REPORT_URL").orNull?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("bfReportUrl").orNull).orEmpty().trim()
        val safeUrl = reportUrl.takeIf { Regex("https://[\\x21\\x23-\\x5b\\x5d-\\x7e]+").matches(it) }.orEmpty()
        if (reportUrl.isNotEmpty() && safeUrl.isEmpty())
            logger.warn("w: BF_REPORT_URL/bfReportUrl must be an https:// URL of printable ASCII without quotes or backslashes; REPORT_URL left empty")
        buildConfigField("String", "HELD_BACK_LANGUAGES", "\"" + heldBackLanguages.joinToString(",") + "\"")
        buildConfigField("String", "REPORT_URL", "\"" + safeUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
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
        // R8 (Play flags unobfuscated DEX). sherpa-onnx's JNI reads classes by name: keep rules in proguard-rules.pro.
        // `-Pminify` turns the same on for debug, to prove the rules on the phone without the 1 GB release bundle. R8
        // neither renames nor optimizes a debuggable build, so that debug is not debuggable: no run-as, no SAY/FACE hooks.
        val minify: com.android.build.api.dsl.ApplicationBuildType.() -> Unit = {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            minify()
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        if (providers.gradleProperty("minify").isPresent) debug { minify(); isDebuggable = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // Play Asset Delivery: one on-demand pack per language (officers, destinations) and one core pack (Whisper).
    // Held-back languages (heldBackLanguages in gradle.properties) keep their pack modules but stay out of the bundle.
    assetPacks += shippedLanguages.map { ":packs:lang_$it" } + ":packs:core_whisper"
    testOptions { unitTests.isReturnDefaultValues = true }
    // The interface language is switchable in-app: ship every shipped language in the base APK.
    // Split per language, a device only got its system languages (en, ru) and any other UI choice fell back to English.
    bundle { language { enableSplit = false } }
    // Only the shipped languages' strings (ours and the libraries'): a phone set to a held-back language gets English.
    androidResources { localeFilters += shippedLanguages }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.google.android.play:asset-delivery:2.3.0")
    // The officer's eyes: front camera + bundled (offline) face detection.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Debug-only hand-gesture measurement spike (HandLandmarks.kt in src/debug): never in a release. Pulls Google's
    // Clearcut uploader like ML Kit; the main manifest already strips its backend and INTERNET.
    debugImplementation("com.google.mediapipe:tasks-vision:1.0.0")
    // Is the answer in the desk's language? Bundled model, on-device, no download (Nano's LANGUAGE flag misread names).
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // System Gemini Nano (AICore): the officer, hints and answer check; ML Kit speech per language.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
