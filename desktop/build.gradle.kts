import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.kotlin.compose)
}

// Soma desktop (cockpit/DESIGN.md §6): the phone's chat, from :chat, in a window on the Macs and the Omarchy boxes.
kotlin { jvmToolchain(17) }

/** The packs and the symbol font, taken from the phone app's own folders so both draw from one set. */
val looks by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/packs")) { into("packs") }
    from(rootProject.file("app/src/main/res/font/nerd_symbols.ttf")) { into("fonts") }
    into(layout.buildDirectory.dir("generated/looks"))
    // The tray's Look menu lists every pack the phone has, from this index.
    val packs = rootProject.file("app/src/main/assets/packs")
    inputs.dir(packs)
    doLast {
        val ids = packs.listFiles().orEmpty().filter { it.isDirectory && (it.resolve("pack.toml").isFile || it.resolve("pack.json").isFile) }.map { it.name }.sorted()
        destinationDir.resolve("packs/index.txt").writeText(ids.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(looks) }

/**
 * The commit this build is from and when it was committed (soma-build.properties), which the update pill compares with the
 * Mac Pro's published build (Updates.kt, tools/soma-release). Asked of Git through providers, so the configuration cache keeps it.
 */
val buildStamp by tasks.registering {
    val commit = providers.exec { workingDir(rootDir); commandLine("git", "rev-parse", "--short=8", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.map { it.trim() }
    val committed = providers.exec { workingDir(rootDir); commandLine("git", "log", "-1", "--format=%ct"); isIgnoreExitValue = true }.standardOutput.asText.map { it.trim() }
    val out = layout.buildDirectory.dir("generated/stamp")
    inputs.property("commit", commit)
    inputs.property("committed", committed)
    outputs.dir(out)
    doLast { out.get().file("soma-build.properties").asFile.apply { parentFile.mkdirs(); writeText("commit=${commit.get()}\ncommitted=${committed.get()}\n") } }
}
sourceSets.main { resources.srcDir(buildStamp) }

dependencies {
    implementation(project(":chat"))
    // This machine's Skia, or another's for a jar built here to run there: -PsomaOs=linux-x64 (MG1) or linux-arm64.
    implementation(providers.gradleProperty("somaOs").map { "org.jetbrains.compose.desktop:desktop-jvm-$it:${libs.versions.compose.multiplatform.get()}" }.getOrElse(compose.desktop.currentOs.toString()))
    implementation(libs.org.json)
    // Runner keys in the macOS Keychain or the Secret Service, never in a file.
    implementation(libs.java.keyring)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.compose.ui:ui-test:${libs.versions.compose.multiplatform.get()}")
}

tasks.test {
    forkEvery = 1
    // -PsomaShots=DIR: the update pill's cards as PNGs there, for a walk (UpdatesTest.shots).
    systemProperty("soma.shots", providers.gradleProperty("somaShots").getOrElse(""))
    // -PsomaWalk=DIR: the update walk against a real runner and a real published build (UpdateWalkTest), its record there.
    systemProperty("soma.walk", providers.gradleProperty("somaWalk").getOrElse(""))
}

compose.desktop {
    application {
        mainClass = "dev.omalauncher.desktop.MainKt"
        // The Keychain library loads its native half.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // A trackpad pinch on the dashboard (desktop slice 5c, Pinch.kt) is heard through Apple's gesture listener.
        jvmArgs += "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
        nativeDistributions {
            // `soma --serve` runs on the JDK's own HTTP server, which a trimmed runtime leaves out unless named.
            modules("jdk.httpserver")
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Soma"
            packageVersion = "1.0.0"
            description = "Soma: one chat with your agents on every machine"
            macOS {
                bundleID = "dev.omalauncher.soma"
                // soma:// links (slice 10) open this app; macOS hands them over as an Apple event (Main.kt).
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array><dict>
                            <key>CFBundleURLName</key><string>dev.omalauncher.soma.link</string>
                            <key>CFBundleURLSchemes</key><array><string>soma</string></array>
                        </dict></array>
                    """
                }
            }
        }
    }
}

// Signed with a certificate this Mac names (somaSignIdentity in ~/.gradle/gradle.properties: per-machine, so out of Git),
// Soma keeps one identity from build to build (its bundle id and team, not each build's hash), and the Keychain's
// "Always Allow" for its runner keys holds across updates. Without one it stays signed ad hoc, and each build is a new app
// to the Keychain. Done here rather than by Compose's own signing, which finds only Developer ID certificates. The
// entitlements are the ones Compose gives an ad hoc build: the JVM's JIT and its native libraries.
val somaSignIdentity = providers.gradleProperty("somaSignIdentity").orNull
if (somaSignIdentity != null) {
    // Taken as plain values here, so the step holds nothing of the build script (the configuration cache keeps it).
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app/Soma.app").get().asFile
    val entitlementsFile = layout.buildDirectory.file("soma-entitlements.plist").get().asFile
    val identity: String = somaSignIdentity
    tasks.matching { it.name == "createDistributable" }.configureEach {
        doLast {
            val app = appDir
            val entitlements = entitlementsFile
            entitlements.writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>com.apple.security.cs.allow-jit</key><true/>
                <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
                <key>com.apple.security.cs.disable-library-validation</key><true/>
                </dict></plist>""".trimIndent(),
            )
            fun sign(file: File) {
                val done = ProcessBuilder("codesign", "--force", "--timestamp=none", "--options", "runtime", "--entitlements", entitlements.path, "--sign", identity, file.path)
                    .redirectErrorStream(true).start()
                val said = done.inputStream.bufferedReader().readText()
                check(done.waitFor() == 0) { "codesign ${file.name}: $said" }
            }
            // Inside out: every library and helper, then the runtime bundle, then the app.
            app.walkBottomUp().filter { it.isFile && (it.name.endsWith(".dylib") || it.name.endsWith(".jnilib") || it.name == "jspawnhelper") }.forEach(::sign)
            sign(File(app, "Contents/runtime"))
            sign(app)
        }
    }
}

// The one-jar build for Linux (-PsomaOs=linux-x64 :desktop:packageUberJarForCurrentOS): a dependency's signature files
// (META-INF/*.SF, *.RSA…) no longer match once everything is in one jar, and Java refused to start it ("Invalid signature
// file digest", seen 2026-10-28 in a linux/amd64 Temurin 17). They are left out; nothing here is checked against them.
tasks.matching { it.name.startsWith("packageUberJar") || it.name.startsWith("packageReleaseUberJar") }.configureEach {
    (this as org.gradle.jvm.tasks.Jar).exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}
