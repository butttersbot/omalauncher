plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

/** Compose Desktop's build for the computer this runs on, for the desktop tests only (as in chat/build.gradle.kts). */
val desktopOs: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) "macos-arm64" else "macos-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

// Soma's UI kit: how things feel, never how they look (docs/SOMA-UI-KIT.md). It knows no pack, no chat and no widget,
// so any Compose app can take it without the launcher's chat: colours are handed in by whoever draws.
kotlin {
    android {
        namespace = "dev.omalauncher.kit"
        compileSdk = 37
        minSdk = 29
        // The phone app compiles to Java 11 bytecode (AGP's default); inline functions from here must match it.
        compilations.configureEach { compileTaskProvider.configure { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } } }
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            // The predictive Back gesture.
            implementation(libs.androidx.activity.compose)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktopOs:${libs.versions.compose.multiplatform.get()}")
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.compose.multiplatform.get()}")
            }
        }
    }
}
