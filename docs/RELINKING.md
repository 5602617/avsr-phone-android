# Rebuilding with a modified SPAKE2 library

shealive uses libadb-android 3.1.1 under its Apache-2.0 option. Its independent
dependency `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` remains
LGPL-3.0. Choosing Apache for libadb does not relicense SPAKE2. The LGPL and GPL
texts are packaged in `app/src/main/assets/licenses/`.

This document provides the LGPLv3 section 4d0 route: rebuild the library, then
recombine the application with the resulting AAR. It replaces both the Java
classes and JNI libraries. Merely replacing one `.so` would not cover the Java
portion compiled into the APK's DEX files.

## Fixed source and build inputs

| Input | Exact source |
| --- | --- |
| SPAKE2 Java / Android 2.2.1 | [MuntashirAkon/spake2-java](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464), commit `7615ddd680b990e14513ebb66eac4cb0dbf82464` |
| C submodule | [MuntashirAkon/spake2-c](https://github.com/MuntashirAkon/spake2-c/tree/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31), commit `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31` |
| Library build | Upstream `build.gradle`, `android/build.gradle`, `android/src/main/cpp/CMakeLists.txt` and `.gitmodules` at the commit above |
| Application | The shealive source revision accompanying the distributed APK, including its build files, resources, source and scripts; see [BUILD.md](BUILD.md) for its independent toolchain and tunnel inputs |

The upstream library uses AGP 8.9.2, compile SDK 35 and build tools 35.0.0. Use
Gradle **8.11.1** and JDK **17** for this library build, separately from the
application's toolchain. Upstream 2.2.1's wrapper points to Gradle 9.0.0; the
commands below deliberately use a separately installed Gradle 8.11.1, matching
the [AGP 8.9 compatibility table](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
The default NDK is 27.0.12077973. Install the Android SDK CMake 3.22.1 package
for the upstream CMake build. Do not use the application's JDK 25 to run this
older Gradle build.

## 1. Obtain and modify the complete library source

The following shell commands use Git and POSIX shell (including Git Bash on
Windows). Keep this checkout outside the application checkout. Set `ANDROID_HOME`
to your SDK, `SPAKE_JAVA_HOME` to JDK 17, and `GRADLE_8_11_1` to the executable
from the official [Gradle 8.11.1 distribution](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip).
On Windows, that executable can be `bin/gradle.bat`.

```sh
git clone --no-checkout https://github.com/MuntashirAkon/spake2-java.git spake2-java
git -C spake2-java checkout --detach 7615ddd680b990e14513ebb66eac4cb0dbf82464
git -C spake2-java submodule update --init --recursive
git -C spake2-java rev-parse HEAD
git -C spake2-java/android/src/main/cpp/spake2-c rev-parse HEAD
```

The last two lines must identify the commits in the table before making your
changes. Unlike a GitHub source archive of the parent repository, recursive
submodule checkout includes the C implementation. Keep all copyright/license
files, including the C submodule's LGPL license. Record your changes as a patch
or source archive alongside your rebuilt AAR.

Prepare the SDK packages through Android Studio's SDK Manager, or its command
line equivalent:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.0.12077973" "cmake;3.22.1"
```

Accept the SDK terms through the normal SDK Manager process. Then build only
the Android library:

```sh
JAVA_HOME="$SPAKE_JAVA_HOME" "$GRADLE_8_11_1" -p spake2-java \
  --no-daemon :android:assembleRelease
```

The output is `spake2-java/android/build/outputs/aar/android-release.aar`.
Retain the Java package, JNI method signatures, library name `spake2`, and
compatible public API when changing the implementation. The application packs
arm64-v8a, armeabi-v7a and x86_64, so the replacement must provide these ABIs.
Use `jar tf android-release.aar` or an archive viewer to inspect `classes.jar`
and `jni/<abi>/libspake2.so`.

## 2. Select the replacement for this application build

Return to the shealive checkout and configure its toolchain as described in
[BUILD.md](BUILD.md), including the six tunnel executables. Set `SPAKE_AAR` to
the absolute path of your rebuilt AAR. Do not copy it over a shared Gradle cache.

```sh
bash ./gradlew --no-daemon -I scripts/relink-spake2.init.gradle \
  "-PrelinkSpake2Aar=$SPAKE_AAR" :app:verifySpake2Replacement
bash ./gradlew --no-daemon -I scripts/relink-spake2.init.gradle \
  "-PrelinkSpake2Aar=$SPAKE_AAR" :app:assembleDebug :app:assembleRelease
```

On Windows PowerShell use `./gradlew.bat` and `$env:SPAKE_AAR` with the same
arguments. The explicit init script excludes the original Maven module from
`:app` configurations and adds the local AAR to `implementation`. The verification
task resolves the debug runtime module graph, checks that the original module is
absent and the local AAR dependency is configured, and prints its SHA-256.
It does not download/transform every runtime artifact or claim an APK
has been rebuilt. Pass **both** `-I` and `-P` to every replacement build; ordinary
builds keep the standard upstream dependency and do not load this script.

Inspect the resulting APK's `lib/<abi>/libspake2.so` entries and their hashes
against the rebuilt AAR, allowing for any documented packaging transformations.
The replacement's Java classes also need to be compiled into the APK; successful
application packaging and execution are separate checks from dependency selection.

## 3. Sign and install your own build

Debug output is automatically signed for the separate `dev.avsr.phone.debug`
application ID. Release output is unsigned unless you provide your own
`AVS_KEYSTORE`, `AVS_KEY_ALIAS`, `AVS_STORE_PASSWORD` and `AVS_KEY_PASSWORD` using
the existing build environment interface. See [BUILD.md](BUILD.md) and Android's
[signing instructions](https://developer.android.com/build/building-cmdline#sign_cmdline).
No maintainer private key is needed to build or self-sign.

A self-signed Release APK cannot update an installation signed by another key.
Use the separate Debug identity for an independent installation, or deliberately
manage a separate device/profile or an uninstall before installing a self-signed
Release. Uninstalling deletes application data: export what you need first.
These are user choices, not actions performed by this build helper. Android
will require regranting permissions and reestablishing debugging access for a
new installation.

## Distribution materials and permissions

The SPAKE2 library and modifications to it retain their LGPL rights. The
application's PolyForm notice does not apply to that independently licensed
library. As required by LGPLv3 section 4, this project's terms do not restrict
modification of the LGPL portions or reverse engineering for debugging those
modifications. This statement does not relicense inherited application code or
grant unrelated commercial rights.

For a binary release, accompany it with the corresponding application source,
the exact library and C submodule source (including any modifications), and
the build/recombination materials above. A self-contained source archive must
include submodule contents, not just the parent repository's gitlink. If sources
are offered by download under GPLv3 section 6d, provide the corresponding source
download instructions next to the binary download; a moving repository homepage
alone is not a substitute for the source of that binary. Preserve LGPL/GPL
texts and notices with redistributed copies. Installation information under
LGPLv3 section 4e depends on the applicable GPLv3 section 6 conditions; this
document does not assume every APK distribution requires a maintainer key.

Verification status (2026-10-25): source/submodule references and build files
were checked against upstream. `verifySpake2Replacement` ran successfully with
the existing unmodified AAR as a local input: the original Maven module was
excluded and the local AAR was selected. This verifies the optional build entry,
not a modified library build. Rebuilding the library with its separate toolchain,
recombining the APK, and checking that replacement on a device remain unverified.
No device installation is performed by these instructions.
