plugins {
    alias(libs.plugins.compose.desktop) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Test JVMs run as background Mac apps, so a test run never takes focus from whatever you're typing in.
subprojects {
    tasks.withType<Test>().configureEach { systemProperty("apple.awt.UIElement", "true") }
}
