# Build tools on the Pixel

Installed in Omalauncher Boss's private Linux environment:

- OpenJDK 25 (Java compiler and signing-key tools), Python, zip and unzip from Arch Linux ARM packages.
- Gradle 9.8.0, copied from the existing official Mac Gradle distribution.
- Google Android API 35 platform and Java D8/apksigner tools, copied from the existing Mac Android SDK.
- Native ARM64 aapt/aapt2, aidl, zipalign and dexdump from [lzhiyong/android-sdk-tools 35.0.2](https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2). These replace Google's x86 Linux executables.

SDK: `/opt/android-sdk`. Standard build-tools directory: `/opt/android-sdk/build-tools/35.0.0`. Tools are discoverable through `/usr/local/bin`; `gradle` exports ANDROID_HOME and ANDROID_SDK_ROOT. `oma-build-check` checks versions and the platform jar. The user's private Gradle configuration points AAPT2 at the native ARM64 executable.

Verification: Java activity compiled on the phone, string resources compiled/linked with AAPT2, bytecode converted to classes.dex with D8, APK assembled and aligned, signed with a disposable smoke-test key, and successfully verified with apksigner. Native AAPT/AAPT2 also read the resulting package metadata. The smoke APK is `~/oma-build-smoke/smoke.apk`; it was not installed as an app. Gradle --version also passed on the phone. This verifies a basic Java Android APK, not every Gradle plugin or an NDK/C++ build.

These tools are stored on this phone, not bundled into the launcher APK. A new phone needs the same toolchain setup after Linux installation. No agent credentials are included in the SDK files.

Native ARM64 archive SHA256: db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2
