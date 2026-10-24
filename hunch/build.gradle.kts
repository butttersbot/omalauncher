plugins { alias(libs.plugins.android.application) }
android {
    namespace = "dev.omalauncher.hunch"
    compileSdk = 37
    defaultConfig { applicationId = "dev.omalauncher.hunch"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
}
dependencies { testImplementation(libs.junit); testImplementation(libs.org.json) }
val mangaFonts by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/packs/manga/fonts"))
    into(layout.buildDirectory.dir("generated/hunch-assets/fonts"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/hunch-assets").get().asFile)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(mangaFonts) }
