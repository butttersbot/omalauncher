import groovy.json.JsonSlurper
plugins { alias(libs.plugins.android.application) }
val publicPrivy = JsonSlurper().parse(file("privy-public-config.json")) as Map<*, *>
android {
    namespace = "dev.omalauncher.hunch"
    compileSdk = 37
    defaultConfig {
        buildConfigField("String", "PRIVY_APP_ID", groovy.json.JsonOutput.toJson(publicPrivy["appId"]))
        buildConfigField("String", "PRIVY_CLIENT_ID", groovy.json.JsonOutput.toJson(publicPrivy["appClientId"]))
        buildConfigField("String", "PRIVY_RP", groovy.json.JsonOutput.toJson(publicPrivy["relyingParty"]))
    }
    buildFeatures { buildConfig = true }
    defaultConfig { applicationId = "dev.omalauncher.hunch"; minSdk = 29; targetSdk = 37; versionCode = 11; versionName = "0.5.0" }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.privy:privy-core:0.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 testImplementation(libs.junit); testImplementation(libs.org.json) }
val mangaFonts by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/packs/manga/fonts"))
    into(layout.buildDirectory.dir("generated/hunch-assets/fonts"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/hunch-assets").get().asFile)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(mangaFonts) }
