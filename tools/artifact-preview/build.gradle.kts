plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.omalauncher.artifactpreview"
    compileSdk = 37
    defaultConfig { applicationId = "dev.omalauncher.artifactpreview"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1" }
}
dependencies {
    implementation(project(":chat"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
}

val previewPacks by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/packs/manga"))
    into(layout.buildDirectory.dir("generated/preview-assets/manga"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/preview-assets").get().asFile)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(previewPacks) }
