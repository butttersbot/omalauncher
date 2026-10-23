import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Soma Messages: a standalone app beside the launcher. It draws in Soma's looks and its shared composer (:chat), and
// reads and answers iMessages through the installed OpenBubbles (features/soma-messages/PLAN.md in ClaudeSync).
android {
    namespace = "dev.omalauncher.messages"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.omalauncher.messages"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

// The looks come from the launcher's own pack files, so both apps draw from one copy. Only the looks Boss uses ship here.
abstract class SomaPacks : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packs: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(packs) { include("manga/**", "city-pop/**", "instrument/**") }
            into(output.dir("packs"))
        }
    }
}
val somaPacks = tasks.register<SomaPacks>("somaPacks") { packs.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/packs")) }
androidComponents { onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(somaPacks, SomaPacks::output) } }

dependencies {
    implementation(libs.androidx.activity.compose)
    // The hinge of a foldable: the unfolded workspace.
    implementation(libs.androidx.window)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    // Soma's looks, its type and the shared composer.
    implementation(project(":chat"))
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
