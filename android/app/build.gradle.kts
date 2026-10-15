plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ylports.imagetopixel"
    buildFeatures { buildConfig = true }
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ylports.imagetopixel"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // The engine is JavaScript/WebGL and has no ABI-specific shared libraries.
        // This APK runs on arm64-v8a (including SM-A155M) and on x86_64 test emulators.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/editorAssets"))
    androidResources { noCompress += listOf("woff2", "mp4", "webm") }
    lint { abortOnError = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val prepareEditor by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("node", "scripts/build-android.mjs", "--no-build")
    inputs.dir(rootProject.file("../_site"))
    inputs.dir(rootProject.file("web"))
    inputs.file(rootProject.file("../scripts/build-android.mjs"))
    outputs.dir(layout.buildDirectory.dir("generated/editorAssets"))
}
tasks.named("preBuild").configure { dependsOn(prepareEditor) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
