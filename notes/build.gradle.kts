import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Soma Notes: a standalone app beside the launcher (Boss, 2026-10-23). Boss's notes and his agents' notes are plain
// files in ~/Notes on the Mac Pro, served by tailshare-serve's /v1/notes; the phone keeps a full copy and works
// offline. Drawn in the Manga look's colours and type (design/soma-notes-mockup.html).
android {
    namespace = "dev.omalauncher.notes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.omalauncher.notes"
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4"
    }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

// The Manga look's fonts, from the launcher's own pack, so both apps draw from one copy.
abstract class MangaFonts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(fonts)
            into(output.dir("fonts"))
        }
    }
}
val mangaFonts = tasks.register<MangaFonts>("mangaFonts") { fonts.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/packs/manga/fonts")) }
androidComponents { onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(mangaFonts, MangaFonts::output) } }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
