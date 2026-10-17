plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Soma's chat: the parts the phone app and the Mac app share. Both targets are JVMs, so common code may use the JDK
// and org.json (Android ships it; the desktop target gets the library). Compose comes with the chat's screens, in slice 2.
kotlin {
    android {
        namespace = "dev.omalauncher.chat"
        compileSdk = 37
        minSdk = 29
    }
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        val desktopMain by getting {
            dependencies { implementation(libs.org.json) }
        }
    }
}
