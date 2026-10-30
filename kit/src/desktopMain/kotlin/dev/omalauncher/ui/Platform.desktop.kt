package dev.omalauncher.ui

// The desktop has no system-wide switch for animations that Compose reads; they always run.
actual fun systemAnimationsOn(): Boolean = true
