plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Single place to bump the app version: major.minor.patch, versionCode = major*10000 + minor*100 + patch.
val appVersionName = "0.4.1"
val appVersionCode = appVersionName.split(".").let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }

android {
    namespace = "io.github.taehunkim.jogakbo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.taehunkim.jogakbo"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing: the key lives outside the repo (see docs/development.md). OndolSaem, Nunsori and Malbotdari let
    // apps signed with their own key in without asking, so release builds of the apps should share one key.
    fun signingProp(name: String): String? = (project.findProperty(name) as String?) ?: System.getenv(name)
    val releaseStore = signingProp("NUNSORI_STORE_FILE")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = signingProp("NUNSORI_STORE_PASSWORD")
            keyAlias = signingProp("NUNSORI_KEY_ALIAS")
            keyPassword = signingProp("NUNSORI_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { generateLocaleConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// Licenses shown in Settings › About: generated from the repo-root files (git-ignored output).
val licensesAsset = tasks.register("generateLicensesAsset") {
    val notices = rootProject.file("THIRD_PARTY_NOTICES.md")
    val license = rootProject.file("LICENSE")
    val out = file("src/main/assets/licenses.txt")
    inputs.files(notices, license)
    outputs.file(out)
    doLast { out.writeText(notices.readText() + "\n\n----------------------------------------\n\n" + license.readText()) }
}
tasks.named("preBuild") { dependsOn(licensesAsset) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":sdk"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Room reads its schema bundle with kotlinx.serialization; an older runtime pulled in transitively breaks migration tests.
    implementation(libs.kotlinx.serialization.core)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasklist)
    implementation(libs.jsoup)
    // Summaries, speech recognition and embeddings run in OndolSaem, reached through its SDK.
    implementation(libs.ondolsaem.sdk)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20260814") // real org.json for JVM unit tests (android.jar has stubs)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
