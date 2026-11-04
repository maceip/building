# Android SDK helper build

`python3 tools/android-sdk/build.py --output app/ide-android/build/native-sdk-tools` builds
the Android-host `aapt`, `dexdump`, `split-select`, and `zipalign` executables. Android
`preBuild` runs this task automatically. It requires Python 3.11+, Git, CMake, Ninja,
`patch`, and `unzip`; its first run downloads the locked source revisions and Google's
NDK r29 beta1. Pass `--ndk /absolute/path` to use that NDK already installed locally.

`sources.lock.json` pins the AOSP repositories and the upstream CMake definitions.
The build targets arm64 Android API 26, uses the static C++ runtime, and checks every
ELF load segment for 16 KB alignment. Executables, source provenance, and license
notices are staged under the output directory. Subsequent builds reuse the local
sources and Ninja objects in the ignored `tools/android-sdk/build` directory.

The same task builds `libsdk-exec-prefix.so`. It relocates legacy executable paths
inside the pinned runtime packages to this app's private prefix, then delegates to
the existing system-linker launcher. It is preloaded only into build processes.
Package extraction also relocates absolute library symlinks into that prefix.

The Gradle runtime also installs checksum-pinned Android builds of Java 17/21, LLVM,
CMake, Ninja, AAPT2, and Node.js into the app's private directory. These are library
and executable payloads; installing the Termux app is not required. The static
ReVanced AAPT2 used by the existing ART build pipeline remains separate, because
Gradle's linker-based child launcher needs a dynamic executable.

For an NDK project, `NdkPackages` downloads the exact requested NDK's headers, link
libraries, build scripts, and metadata from Google's repository and checks the archive
checksum. It excludes desktop executables and supplies the pinned Android-host LLVM
21.1.8 driver and its matching compiler headers/runtime resources. The requested
NDK still supplies the Android API headers and C++ standard library. CMake uses
the installed Android-host version (currently 4.4.4), with
the substitution reported in Gradle's log; project source files are not rewritten.
These are compatibility builds, not a claim of byte-identical output to a desktop
build using Google's compiler.

On-device acceptance uses `GradleProjectDeviceTest` with `gradleProject`, `gradleTask`,
`gradleLabel`, and optional `expectedApplicationId` instrumentation arguments. It runs
the actual Gradle host, checks the imported application ID, and requires a freshly
produced APK. Run only against disposable project copies: the test removes previous
APKs in their `build/outputs/apk` directories before building.
