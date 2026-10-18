plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

/** Compose Desktop's build for the computer this runs on: the Macs (arm64) and the Omarchy boxes (x64, arm64). */
val desktopOs: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) "macos-arm64" else "macos-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

// Each desktop test class in a JVM of its own: an off-screen scene and a UI test in one process trip over each other.
tasks.matching { it.name == "desktopTest" }.configureEach { (this as Test).forkEvery = 1 }

// Soma's chat: the parts the phone app and the Mac app share. Both targets are JVMs, so common code may use the JDK
// and org.json (Android ships it; the desktop target gets the library).
kotlin {
    android {
        namespace = "dev.omalauncher.chat"
        compileSdk = 37
        minSdk = 29
        // The phone app compiles to Java 11 bytecode (AGP's default); inline functions from here must match it.
        compilations.configureEach { compileTaskProvider.configure { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } } }
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // The widget language: widgets render from shared code on every screen (Soma Canvas slice 7a).
            api(project(":owl"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidsvg)
            // BackHandler, for the phone's Back gesture.
            implementation(libs.androidx.activity.compose)
        }
        val desktopMain by getting {
            dependencies { implementation(libs.org.json) }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                // Skia for this computer, so a test can draw the screens off-screen.
                implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktopOs:${libs.versions.compose.multiplatform.get()}")
                // Finding and tapping what a screen shows, in a test.
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.compose.multiplatform.get()}")
            }
        }
    }
}
