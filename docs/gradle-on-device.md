# Gradle on device

Implementation plan for replacing the in-process native build pipelines with a real Gradle
invocation, and for making `externalNativeBuild` use the Android-host clang this repo already
builds.

The build runs on a **bionic OpenJDK**, started by the system dynamic linker. PRoot is not part
of the design. Neither is a glibc guest, qemu-user, or `dlopen` of `libjvm.so` into the IDE
process.

The editor, indexer, and language backends stay. What goes away is the native task graph as the
thing that compiles a user's project into an APK.

## The decision

Gradle and the Android Gradle Plugin are Turing-complete programs that fork JVMs, run shell
scripts, and exec host binaries. They already exist. The faithful port is the one Vivari made
for Node and the one AndroidIDE made for Gradle: run the real program on a small platform
layer, and stop reimplementing it.

This repo's native pipeline (`:jvm-build`, `:android-support`'s `AndroidBuildSystem`, the
in-process D8/R8/kotlinc path) is the analogue of Vivari's retired Path A: a hand-written
runtime that covers the declarative majority and records a diagnostic for the rest. `GradleImport`
is that diagnostic. It stays as a reader for the window before a toolchain is installed. It
stops being how a project is built.

## What the other projects actually did

### Vivari (`maitrungduc1410/vivari`)

Vivari runs Node in the browser by vendoring Node's own `lib/*.js` and `internal/*` and
replacing only `internalBinding(...)` with shims over a Rust/Wasm filesystem. The first
version hand-wrote `fs`, `http`, and an npm installer. That version was deleted once the real
`lib/` ran. npm is the real npm CLI, packed by `scripts/vendor-npm.mjs` into one gzipped
asset and unpacked into the virtual filesystem at boot. Native addons cannot run; `node-gyp`
is a stub, and the package's wasm fallback is what loads.

The transferable rule: the binding layer is the platform (filesystem, process spawn, network).
Everything above it is upstream code, pinned and regenerated, not rewritten when a tool
misbehaves. Applied here, that upstream program is Gradle. Vivari itself is a Node port,
with its own `lib/`, a vendored npm, and stubs where native addons would be. Putting that
port on the phone so a Gradle task can exec `node` starts a second build system. We do
not ship it.

### Termux (`termux/termux-packages`)

**OpenJDK** is a real port, not a download of a glibc JDK. `packages/openjdk-21/build.sh`
(21.0.12 as of this writing) configures upstream `jdk21u` with `--openjdk-target` set to the
Android host triple, clang, `-D__ANDROID__=1 -D__TERMUX__=1`, a server JVM, and about forty
patches. The patches that matter for us:

- `libjvm.so` is found from `/proc/self/maps` when the process was not `exec`'d the usual way
  (patches 0013 and 0017).
- Hardcoded `/system` and `/usr` paths are rewritten into the prefix (0025, 0037).
- Android's `strerror_r`, tagged pointers, and `statx` are special-cased.
- Link-time optimization is on (`__jvm_features="link-time-opt"`). That is a size flag inside
  Termux's package build. It has nothing to do with how the binary is executed.

The image is installed at `$PREFIX/lib/jvm/java-21-openjdk/`. Dependent packages are told
`JAVA_HOME` through `etc/profile.d/java.sh`. Runtime libraries are `libandroid-shmem`,
`libandroid-spawn`, `libiconv`, `libjpeg-turbo`, `zlib`, `littlecms`, `alsa-plugins`. A JDK
tarball that does not bring this closure does not start. Adoptium and the Oracle Linux
builds are glibc images; they are the wrong binaries on bionic.

**Gradle** is not a port. `packages/gradle/build.sh` downloads the upstream `-bin` zip
(9.8.1 as of this writing), strips the `.bat` launchers, copies the tree to
`$PREFIX/opt/gradle`, and symlinks `bin/gradle` into `$PREFIX/bin`. The package is
`PLATFORM_INDEPENDENT` and depends on `openjdk-21 | openjdk-25 | openjdk-17`. One package
version means one Gradle version. A project that pins a different wrapper must not be
forced onto it.

**Execution on Android 10+.** `targetSdkVersion >= 29` puts the app in an SELinux domain
that cannot `exec` files labeled `app_data_file`. `dlopen` of those files is still allowed.
`termux-exec` is an `LD_PRELOAD` that rewrites `execve` to
`/system/bin/linker64 /absolute/path/to/elf` (or `/system/bin/linker` for 32-bit). The kernel
executes the system linker, which maps the target. The preload also rewrites `#!/usr/bin/env`
shebangs onto `$PREFIX/bin`. It does not see raw `syscall(SYS_execve)`. OpenJDK and Gradle go
through libc, so they are covered. The preload has to be in the environment of the first
process and inherited by every child. Unsetting it to "be safe" around a nested tool is how
the next `java` fails.

### AndroidIDE (`AndroidIDEOfficial/AndroidIDE`)

Unmaintained, and still the only IDE that ran real AGP on a phone. The decisions worth
copying, and the ones not to:

1. **The IDE process stays on ART.** A foreground service
   (`GradleBuildService`) spawns
   `$JAVA_HOME/bin/java -jar tooling-api-all.jar`. That JVM is the Gradle Tooling API
   client. It speaks LSP4J over stdin/stdout back to the app
   (`ToolingServerRunner`, `tooling/impl/Main.java`). The app never loads Gradle's jars.

2. **The project's wrapper picks the Gradle version.** `GradleDistributionParams` defaults
   to `GRADLE_WRAPPER`. A missing `gradlew` is repaired by unzipping a `gradle-wrapper.zip`
   shipped in assets (`doInstallWrapper`). There is no `$PREFIX/opt/gradle` on the build
   path. Termux's single `gradle` package would be the wrong tool here.

3. **The JDK and the SDK are downloaded, not baked into the APK.** The APK's native
   bootstrap is a zip of a Termux-style rootfs, checksum-pinned and `incbin`'d by
   `TerminalBootstrapPackagesPlugin` into the bootstrap object
   (`bootstrap-aarch64.zip` and friends, from
   `AndroidIDEOfficial/terminal-packages`). On first run `idesetup` (`termux/application`
   assets) `pkg install`s `openjdk-17` or `openjdk-21` and downloads SDK build-tools from
   `AndroidIDEOfficial/androidide-tools`. JDK 21 is warned as Gradle 8.5+ only. `JAVA_HOME`
   is written to `$PREFIX/etc/ide-environment.properties`. Their `Environment.JAVA_HOME`
   constant points at `$PREFIX/opt/openjdk`, while the Termux package installs into
   `$PREFIX/lib/jvm/java-21-openjdk/`. The implementation must set `JAVA_HOME` from the
   directory that actually contains `lib/server/libjvm.so`, not from a copied constant.

4. **Maven's aapt2 is never used.** Every build appends
   `-Pandroid.aapt2FromMavenOverride=<android-built aapt2>`. The aapt2 AGP would download
   is a linux-x86_64 ELF. This repo already fetches an Android aapt2 and zipalign into
   `jniLibs` (`fetchAndroidBuildTools` in `:ide-android`). That binary is the override.

5. **AGP 7.2.0 was their floor** because their tooling model was a fork of AGP's builder
   model (`tooling/builder-model-impl`). That fork is frozen. We do not vendor it. The
   Tooling API's own `AndroidProject` / `Variant` model is the contract, and the floor is
   whatever current AGP that model still reads. Projects older than that stay on
   `GradleImport` and are told so.

6. **NDK was declined.** Their README says the SDK manager cannot install the NDK because
   those tools are not built for Android, and they had not built an NDK either. That is
   the gap this repo has already closed for the compiler, and the section below is how it
   gets closed for Gradle.

### Three more runtimes, and why their mechanisms are not ours

**PojavLauncher** (`PojavLauncherTeam/PojavLauncher`,
`android-openjdk-build-multiarch`) ships JRE 8, 17, and 21 inside the APK under
`app_pojavlauncher/src/main/assets/components/jre*`. CI also builds a no-runtime APK with
those directories deleted, because the JREs dominate the package. At runtime
`JREUtils.initJavaRuntime` `dlopen`s `libjli.so` and `libjvm.so` into the launcher
process and calls into the VM. That works for Minecraft, which is one process they
control. It does not give Gradle a `java` binary it can fork for the daemon, the worker
API, or the Kotlin compiler. A JDK that cannot `exec` another JDK cannot run Gradle.
Fold Craft Launcher (`FCL-Team/FoldCraftLauncher`, `FCL-Team/Android-OpenJDK-Build`) is
the same shape for JDK 8, 11, 17, and 21.

**Xed-Editor** (`Xed-Editor/Xed-Editor`) and **Acode** (`Acode-Foundation/Acode`) ship
PRoot as `libproot.so` plus a loader in `jniLibs`, then download a foreign rootfs
(Ubuntu `sandbox.tar.gz`, Alpine) and `apt`/`apk` install OpenJDK from that distro.
Those JDKs are glibc (or musl) binaries. PRoot is what makes them run, by `ptrace`.
Xed's own tracker (`#639`, `#1445`) records `execve` failing on current Samsung kernels,
and draft PR `#1459` is the migration off PRoot onto a bionic rootfs, with OpenJDK still
unchecked on the porting list. Acode's `init-sandbox.sh` is the same PRoot command line.
We do not take this path.

**Nix-on-Droid** (`t184256/nix-on-droid`, app fork `nix-community/nix-on-droid-app`)
installs a Nix bootstrap under PRoot and then can install `pkgs.openjdk` and
`pkgs.gradle` as prebuilt aarch64 Linux binaries. Same objection: the binaries run
because PRoot rewrites their syscalls. The useful observation is only that a real
Gradle and a real JDK exist as ordinary aarch64 software once the kernel interface looks
like Linux. On bionic, Termux's OpenJDK is that software.

## What this repo already has

| Piece | Where | Role after this work |
| --- | --- | --- |
| `BuildSystem` SPI | `build-system/build-api` `Build.kt` | Unchanged. A Gradle build is one session, not a DAG of our tasks. |
| Native Java pipeline | `:jvm-build` | Stop registering it for projects bound to Gradle. |
| Native Android pipeline | `:android-support` `AndroidBuildSystem` | Same. Layout preview may keep the resource linker; that is not a second APK builder. |
| Static Gradle reader | `:ide-core` `GradleImport`, `GradleProjectImporter`, `BuildSystemId.GRADLE_COMPAT` | Pre-toolchain preview and the fallback when sync cannot run. |
| aapt2 / zipalign | `:ide-android` `jniLibs`, `fetchAndroidBuildTools` | The `aapt2FromMavenOverride` binary. Already an Android ELF in `nativeLibraryDir`. |
| Process isolation design | `docs/build-process-isolation.md` | Superseded for user builds. The Gradle JVM is the other process. Do not finish the ART `:build` daemon for the native pipeline. |
| On-device clang | `tools/ndk-toolchain/build-llvm-android.sh`, `:samples:ndk-plugin` | The NDK's compiler. See below. |
| Distribution constraints | `docs/fdroid-izzyondroid.md` | A downloaded JDK is the same class of blob as the prebuilt aapt2. It stays out of the APK. |

`minSdk` is 26 and `targetSdk` is 36 (`:ide-android`). The W^X exec restriction applies.
Nothing in the tree mentions PRoot or `termux-exec` today.

## Target shape

```
IDE process (ART)
  editor, index, Kotlin analysis, C/C++ diagnostics via jniLibs clang
  │
  │  spawn, stdin/stdout
  ▼
/system/bin/linker64  $PREFIX/lib/jvm/java-17-openjdk/bin/java
  LD_PRELOAD=$PREFIX/lib/libtermux-exec.so
  LD_LIBRARY_PATH=$PREFIX/lib
  JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME
  │
  │  Gradle Tooling API
  ▼
Gradle daemon (same JDK, children inherit the preload)
  AGP, Kotlin compiler, D8/R8 as ordinary JVM tools
  aapt2 = our jniLibs binary, via android.aapt2FromMavenOverride
  NDK clang / cmake / ninja = bionic binaries under $SDK/ndk and $SDK/cmake
```

`GRADLE_USER_HOME` lives under the app's files directory. The wrapper downloads the
distribution the project named. The prefix has no `gradle` binary of its own.

## Phase 0 — prefix and exec

A small bootstrap. Contents are the [build-system binaries](#build-system-binaries):
one `sh` that can run `gradlew`, `ca-certificates`, the shared libraries the JDK
needs, and `libtermux-exec.so`. The list is closed there.

Packaging follows AndroidIDE's bootstrap and this repo's own NDK rule
(`samples/ndk-plugin/README.md`):

- Executables that the IDE process itself will `exec` without the preload go in
  `jniLibs` with `useLegacyPackaging = true`, so the installer extracts them into
  `nativeLibraryDir`.
- The bootstrap archive is `incbin`'d or stored as a non-executable asset and unpacked
  into `$PREFIX` (`context.filesDir/usr`). Those files are on the noexec mount. They
  become runnable only through the linker, which is why the preload exists.
- Checksums are pinned. A mismatch is a failed setup, not a retry loop.

First process, every time, is literally:

```
/system/bin/linker64 $JAVA_HOME/bin/java -version
/system/bin/linker64 $PREFIX/bin/sh -c 'java -version'
```

with `LD_PRELOAD` and `LD_LIBRARY_PATH` set, once per installed image. Both must
print that image before any Gradle work is attempted. The first proves the JDK.
The second is how `gradlew` starts it.

The IDE process does not set `LD_PRELOAD` on itself. The preload intercepts `execve`
inside the build process tree only.

## Phase 1 — JDK and SDK

Download two pinned bionic JDK images, 17 and 21, built the way
`packages/openjdk-21/build.sh` builds them. Prefer a rebuild we control (their patches
applied to the upstream tag, checksum recorded) over a silent dependency on
`packages.termux.dev`. Install each at
`$PREFIX/lib/jvm/java-<ver>-openjdk/` including the `.so` closure. `JAVA_HOME` for a
build is that directory.

JDK selection is per project. AGP 9.0 requires JDK 17 or newer and Gradle 9.1.0 or
newer; the Gradle 9 daemon requires JVM 17. JDK 17 is the floor, including for AGP
8.4. JDK 21 is used when the project asks for a 21 toolchain. A wrapper of 8.5 or
of 9.x does not by itself select 21.

| Request | JDK |
| --- | --- |
| AGP 8.4 through current 9.x, no toolchain above 17 | 17 |
| A Java or Kotlin toolchain of 21 | 21 |
| `jvm.toolchain.vendor` pinned to a vendor we do not ship (JetBrains JBR) | our JDK of the requested major, with auto-download off |

`org.gradle.java.installations.auto-download=false` and
`org.gradle.java.installations.paths` pointing at the two images are set on every
launch. A vendor pin must not make Gradle download a desktop JBR. The JBR comment
in a multiplatform project is about a desktop runtime; the Android app uses our
bionic image of the same major version.

A project `gradle.properties` that sets `org.gradle.jvmargs=-Xmx6g` must not win.
The Tooling API launch passes the device-sized value as a command-line
`-Dorg.gradle.jvmargs`, which outranks the project file.

A project whose wrapper is older than the Tooling API client we ship is reported and
left on `GradleImport`. We do not silently run it on the native pipeline. The cohort
below bottoms out at Gradle 8.6 / AGP 8.4, which current Gradle still accepts. That
is the practical floor, not AndroidIDE's frozen AGP 7.2 model.

SDK layout under `$ANDROID_HOME`:

- `platforms/android-<api>/android.jar` — start from the jar already bundled as an
  asset; download further platforms on demand, including the minor the project
  names (`compileSdk = 37`, and AGP's `compileSdk { version = release("37.2") }`).
  The jar is data, not code. The bundled jar is not enough for this cohort: the
  apps below ask for 34, 35, 36, 37, and 37.2.
- `build-tools/<ver>/` — `aapt2` and `zipalign` (the jniLibs binaries; a path under
  `$ANDROID_HOME` is exec'd through the preload) and a bionic `aidl` (Termux's
  `aidl` package). AGP 8 leaves AIDL off unless the module turns it on; a module
  that turns it on execs this binary. `android.aapt2FromMavenOverride` is still
  passed on every Tooling API launch, the way `GradleBuildService.getBuildArguments`
  does. Signing uses AGP's in-process apksig library. Google's `apksigner` ELF is
  not part of this directory.
- `licenses/android-sdk-license` — the standard SDK license hash, so configuration
  does not stop to prompt.
- `cmdline-tools` is optional. We do not need Google's sdkmanager to fetch an NDK
  or a cmake that we will then be unable to exec.

`<ver>` is every `buildToolsVersion` the project names, plus the default for its
AGP. The directories are aliases: one payload, `source.properties` `Pkg.Revision`
equal to the directory name. The same alias is how CMake and the NDK avoid a
Google download. `GradleImport` already reads these version strings; the setup
step materializes the directories before the first real configure. A version that
only appears from a convention plugin is created when the tooling model reports
it, and the configure is retried once.

Global `gradle.properties` in `GRADLE_USER_HOME`, written by us, not by the project:

- `android.aapt2FromMavenOverride=<nativeLibraryDir>/libaapt2.so` (the name we
  already ship)
- `org.gradle.jvmargs` sized from a fraction of device RAM, not a desktop default
- `org.gradle.workers.max=2`
- `kotlin.compiler.execution.strategy=in-process` so the Kotlin daemon is not a
  second full JDK
- `org.gradle.daemon.idletimeout` short enough that a phone reclaims the heap

D8 and R8 now run inside that JDK as AGP's own dependencies. The forked
`dalvikvm64` path (`ForkedD8Dexer`, `ForkedR8Shrinker`, `R8ForkSupport`) is for the
native pipeline and is not reused.

## Phase 2 — tooling server

New JVM module `build-system/gradle-tooling`, plain Java, not an Android library. It
depends on the Gradle Tooling API and is launched as `java -jar`. It is shaded so
the Gradle distribution's own jars do not clash with it.

Protocol is a small length-prefixed JSON stream, or LSP4J if the lifecycle matches
AndroidIDE's closely enough to be worth it. Do not copy
`tooling/builder-model-impl`. Messages:

- `initialize(projectDir, distribution, javaHome, androidHome, args)`
- `fetchModel` → modules, variants, source sets, dependencies, sync issues
- `execute(tasks, args)` with task progress, stdout, and a cancel
- `shutdown`

Distribution type defaults to the wrapper. If `gradlew`,
`gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties` are missing,
generate them pointing at one pinned current Gradle, the way AndroidIDE unzips a
wrapper. Do not download a distribution inside the APK.

Every launch passes one `--init-script`. It lives in the prefix and is not written
into the project. It narrows ABIs as the ABI section describes, applies the CMake
version fallback when an alias is rejected, and points every `JavaExec` task at
the `java` from `JAVA_HOME`.

The server runs in a foreground service. `Binder` death, or the JVM exiting, becomes
`BuildState` failed with the process's last stderr lines. The next build starts a
new JVM. Idle timeout destroys it.

On the IDE side, `GradleBuildSystem` implements `BuildSystem` with
`BuildSystemId("gradle")`. `createBuildGraph` returns a single session task. Gradle's
own task progress feeds the existing Steps list and `BuildLogSink`. Compiler lines
still go through `CompilerOutputParser`. We do not synthesize a `Task` per Gradle
task; the fingerprints and the cache are Gradle's.

Model application: the tooling model is mapped onto `ExternalProjectModel` by a new
importer path that records `BuildSystemId("gradle")` rather than `GRADLE_COMPAT`.
`GradleImport` still runs first so the editor has a project before the JDK exists,
and is replaced when the tooling sync succeeds. A tooling failure leaves the static
model in place and says that the build files were not executed.

`BuildFileWriter` for this id edits `dependencies { }` the way `GradleBuildFileWriter`
already does, then marks the tooling model stale.

## Phase 3 — cut the native pipeline over

A project whose root contains `settings.gradle` or `settings.gradle.kts` binds to
`gradle` once Phase 2 has produced a model. Until then it stays `gradle-compat` and
the banner already implemented in `GradleCompatBanner` stays honest.

`IdeServices` stops calling `AndroidBuildSystem` / `JavaBuildSystem` for that id.
Run becomes: tooling `execute` of one Android application task, then the existing
`ApkInstallerImpl`. Install and launch are already solved.

The task comes from the tooling model, not from a hardcoded name. Take the
modules that apply `com.android.application`. Keep variants whose build type is
debug. If several remain, pick the one with the shortest flavor list, preferring
a variant named `debug`, then `defaultDebug`, then the model's default. The task
is `assemble<Variant>` on that module (`assembleDebug`, animeko's
`assembleDefaultDebug` on `:app:android`). Building every flavor combination is
how a missing signing key or a TV flavor fails an otherwise good phone build.
The execute request does not include an iOS or desktop task, and it does not
turn on a local property that would configure one.

Delete only after a device has assembled the same fixture the native pipeline
assembles today (a single-module app with a resource, a Kotlin source, and a
dependency). The deletion list, in order:

1. The registration that selects `AndroidBuildSystem` and `JavaBuildSystem` for
   projects bound to `gradle`.
2. The on-device dex fork as a user-build strategy.
3. The native task classes, once nothing in the editor calls them. Layout preview's
   resource relink is the likely survivor; it keeps using aapt2 directly and does
   not go back through `AndroidBuildSystem`.

`:jvm-build` and the task engine stay in the tree for plugins and for non-Gradle
modules until those callers are gone. The engine is not the thing being ripped
out. The pipelines that pretend to be AGP are.

Desktop (`:ide-desktop`, `:build-cli`) uses the same tooling module against the
host JDK. No prefix, no linker. One implementation.

## Phase 4 — NDK

### What is already true

`tools/ndk-toolchain/build-llvm-android.sh` builds LLVM 18.1.8, the clang NDK r27c
ships, as an Android arm64 multicall binary. `:samples:ndk-plugin` packages the
executable half in `jniLibs` (`libllvmtools.so`, `libld-gnu-lld.so`, `libLLVM.so`,
`libclang-cpp.so`, `libc++_shared.so`) and the read-only half
(`lib-clang/18`, sysroot headers, one ABI of link libraries, `libc++_shared.so`)
as `assets/toolchain.zip`. `NdkToolchain` execs the jniLibs binary directly from
the IDE process, which is legal, and always passes `--target`, `-resource-dir`,
`--sysroot`, and `--ld-path`, because a flat `lib*.so` directory is not a `bin/`
next to a `lib/`. `NdkBuildPlugin` compiles `:<module>:compileNative` from the
`[ndk]` facet and hands the `.so` to `mergeNativeLibs`. It does not read
`CMakeLists.txt`. The facet exists so a phone can build C++ without carrying CMake.

That plugin path keeps working. Editor diagnostics, completion, and JNI checks
stay on `NdkToolchain` and keep exec'ing `nativeLibraryDir`. They do not wait for
Gradle, and they do not move into the prefix.

Google's NDK has no Android host. Every `clang`, `cmake`, and `ninja` in an
official NDK or SDK cmake package is `linux-x86_64`, `darwin-x86_64`, or
`windows-x86_64`. Running those under PRoot or qemu-user is the approach this
plan does not take. AndroidIDE stopped at the same fact and shipped no NDK.

### What AGP and CMake actually exec

`android.toolchain.cmake` (NDK r27c, which matches our clang) sets:

```
CMAKE_HOST_SYSTEM_NAME == Linux  →  ANDROID_HOST_TAG = linux-x86_64
ANDROID_TOOLCHAIN_ROOT = $NDK/toolchains/llvm/prebuilt/$ANDROID_HOST_TAG
CMAKE_SYSROOT          = $ANDROID_TOOLCHAIN_ROOT/sysroot
```

The host tag is the OS name, not `uname -m`. An Android device reports Linux, so
AGP and CMake look up `linux-x86_64` even though the process is aarch64. They then
exec:

- `$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang` (and `clang++`, `llvm-ar`,
  `llvm-strip`, `ld.lld`)
- `$NDK/prebuilt/linux-x86_64/bin/make` for `ndk-build`
- `$SDK/cmake/<version>/bin/cmake` and `ninja`

The directory name is a label. The ELF inside it has to be aarch64. Nothing in
that lookup checks the machine type.

### The tree we install

The synthetic NDK is installed at `$ANDROID_HOME/ndk/<revision>`, and
`source.properties` `Pkg.Revision` is that same string. `<revision>` is the string
the project asks for (`ndkVersion`), or AGP's default when the project sets none.
AGP looks up `$SDK/ndk/<that version>` and, once `ndkVersion` is set, ignores the
deprecated `ndk.dir`. A missing directory makes AGP download Google's
linux-x86_64 NDK, which we cannot exec.

Our codegen is LLVM 18.1.8, the clang NDK r27c ships. The directory name can still
be a later revision so the locator is satisfied: Signal pins `28.0.13004108`, AGP
9's default is r28c `28.2.13676358`, Mihon pins `29.0.14206865`. A matching
directory is enough for `llvm-strip` and for packaging a prebuilt `.so`, which do
not need that clang's codegen. A project that compiles C or C++ is told the
compiler is r27c. We do not fetch Google's NDK to paper over the version string.
An init script rewrites `ndkVersion` only when we refuse to publish a tree under
the requested name. The default is to publish the tree under the requested name.

`local.properties` gets `sdk.dir`. Scripts are vendored from NDK r27c (they are
text, host-independent, Apache-2.0):

- `build/cmake/android.toolchain.cmake` and the hooks it includes
- `build/ndk-build`, `build/core` for projects that use `Android.mk`
- `sources/android/native_app_glue` (the plugin already carries this)
- `source.properties`, `meta/abis.json` or the equivalent r27c uses so the
  locator accepts the directory

Binaries, all bionic aarch64:

| Path under the NDK | Source |
| --- | --- |
| `toolchains/llvm/prebuilt/linux-x86_64/bin/clang` | our multicall driver, **file name `clang`** so argv[0]'s stem selects the C driver |
| `clang++`, `clang-cl` | symlinks to `clang`. App-private ext4 supports symlinks; the APK does not, and this tree is not in the APK |
| `ld.lld` | the same binary under the name lld's progname parser accepts. The jniLibs name `libld-gnu-lld.so` exists only because an APK forced a `lib*.so` name. It is not used here |
| `llvm-ar`, `llvm-nm`, `llvm-strip`, `llvm-objcopy`, `llvm-ranlib`, `llvm-readelf` | symlinks. The driver dispatches on the stem |
| `toolchains/llvm/prebuilt/linux-x86_64/sysroot` | the r27c unified headers, plus every `usr/lib/aarch64-linux-android/<api>` directory that sysroot already contains, `libc++_shared.so`, `libc++_static.a`. One API level is what the editor zip ships today; the Gradle tree takes the full arm64 set, which is data, not a new clang |
| `toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18` | clang's resource headers, in the relative place stock clang computes from `bin/clang`. This removes the need for the plugin's explicit `-resource-dir` on the Gradle path |
| `prebuilt/linux-x86_64/bin/make` | Termux `make`, or a rebuild of GNU make for bionic |

`libLLVM.so` and `libclang-cpp.so` sit beside the driver on `LD_LIBRARY_PATH`
for the build, the same requirement `NdkToolchain.run` already has. The Gradle
daemon's environment includes that directory.

CMake and ninja are Termux's bionic packages (or a rebuild of the same versions).
One pair of binaries is installed under every `$ANDROID_HOME/cmake/<version>/`
the project or its AGP default names, with `source.properties` `Pkg.Revision`
set to that version. They are not the SDK manager's linux-x86_64 binaries
renamed. If AGP then rejects the binary because `cmake --version` disagrees with
`Pkg.Revision`, the init script sets the CMake version to the binary's real
version at `projectsEvaluated`. The alias is the first try.

The tree is unpacked into app storage. Those files are noexec. They run because
the Gradle process has `termux-exec` loaded, so CMake's `exec` of `bin/clang`
becomes `linker64` plus that absolute path. This is the same mechanism as `java`.
There is no second sandbox. The IDE process does not exec this tree; it keeps
using `nativeLibraryDir` for editor clang, where `exec` works without the preload.

`clang.cfg` next to `bin/clang` is the escape hatch if the relative resource dir
is wrong for our build (the plugin's comment in `commonFlags` is exactly this
bug in a flat layout). Prefer the standard relative layout so a `CMakeLists.txt`
that invokes clang the way the NDK does needs no extra flags. Keep
`-Wl,-z,max-page-size=16384` by vendoring the r27c toolchain file, which passes
it. Our plugin adds `PAGE_SIZE_FLAGS` by hand because its clang has no NDK
defaults; the Gradle path gets the defaults from the toolchain file instead.

### ABI

`LLVM_TARGETS_TO_BUILD=AArch64` is a size decision in `build-llvm-android.sh`
(each extra backend is on the order of 8–15 MB). The on-device APK is
`arm64-v8a`. The init script writes that into `ndk.abiFilters`, into
`externalNativeBuild` cmake and ndk-build filters, and into `splits.abi`, and it
turns `isUniversalApk` off. ABIs that were listed and then removed are a
diagnostic on the build. The assemble continues. A project that compiles C++ for
four ABIs produces the arm64 library and an installable APK. A project that only
packages prebuilt `.so` files does the same: `llvm-strip` runs on the arm64 ELF,
and the other slices are omitted from this APK. An `abiFilters` list is not a
failed build.

Prefab is already covered. AGP runs it as `java` on `com.google.prefab.cli.AppKt`
once per ABI. After the filter there is one ABI, and the CLI is a jar.

CMake passes `--target=aarch64-linux-android<minSdk>`. The sysroot libraries for
every arm64 API level in the r27c tree are what that triple links against. The
acceptance fixture uses `minSdk` 26. A Java or Kotlin assemble does not link
this sysroot.

### What happens to `NdkBuildPlugin`

For a project bound to `gradle`, `compileNative` is not registered. AGP's
`externalNativeBuild` produces the `.so`, and AGP packages it, including
`libc++_shared.so` from the sysroot when the STL is `c++_shared`. The plugin's
`BuildPlugin` remains for a module that still builds through the native pipeline.

The `[ndk]` facet and the three templates stay for projects that have no
`CMakeLists.txt`. A Gradle project that already has `externalNativeBuild` is
not rewritten into a facet. The two descriptions of a native build are not
merged.

Editor features do not change: `syntaxCheck`, `complete`, and the JNI checkers
keep calling the jniLibs clang with `commonFlags()`. When both are present they
must be the same LLVM (18.1.8 / r27c sysroot) so a squiggle and a Gradle failure
name the same line.

### NDK acceptance

On a device, after Phase 0's `java -version` already passed:

1. `linker64 $SDK/cmake/<ver>/bin/cmake --version` and `ninja --version` print
   the pinned versions.
2. `linker64 $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version`
   prints LLVM 18.1.8, and `clang++ -###` shows the C++ driver.
3. A fixture Gradle project with `externalNativeBuild { cmake { } }`, one
   `.cpp`, `abiFilters += "arm64-v8a"`, and `ndkVersion` equal to our
   `Pkg.Revision` runs `assembleDebug`. The APK contains
   `lib/arm64-v8a/lib*.so`, page-aligned to 16 KB.
4. The same compiling project with `abiFilters` including `x86_64` still
   assembles. The APK contains `lib/arm64-v8a/` and no `lib/x86_64/`. The
   Problems list names `x86_64` as skipped. A fixture that only packages prebuilt
   `.so` files, with no `externalNativeBuild`, also assembles an arm64 APK.
5. A flavored fixture has no task literally named `assembleDebug` for the variant
   we want. The model picks one debug variant and that assemble task is what
   runs.
6. Opening a `.cpp` in that project still underlines an error via
   `NdkToolchain.syntaxCheck` without a Gradle sync.

## Build-system binaries

<a id="build-system-binaries"></a>

A host binary on `PATH` is part of this build system when Gradle, AGP, or the NDK
execs it by name while assembling the Android app. The binary and the shared
libraries it needs in order to start are what we ship. The package that built it,
and anything that binary would itself download, are not.

| Binary | Who execs it |
| --- | --- |
| `java` (17 and 21), with the JDK `.so` closure | Gradle, AGP, Kotlin, D8, R8, prefab |
| `sh` | `gradlew` |
| `aapt2`, `zipalign` | AGP. Already in `jniLibs` |
| `aidl` | AGP, only when the module turns AIDL on. One ELF in the build-tools directory |
| `clang` and the `clang++` / `ld.lld` / `llvm-*` names | CMake, ndk-build, AGP's strip |
| `cmake`, `ninja`, `make` | AGP, for `externalNativeBuild` |

`ca-certificates` and `libtermux-exec.so` are on that list because `java` cannot
fetch a wrapper or fork a child without them. `android.jar`, the license hash,
and the arm64 sysroot are data those binaries read.

`sh` is one program, on `PATH` as `sh`. A BusyBox build is acceptable only when
that single name is installed. The other applets are how a prefix turns into a
distro.

### JAR tools, in scope

A dependency that arrives as a JAR is the same build system. The daemon already
has a `java`. KSP, kapt, Hilt, Room, data binding, desugaring, Compose, SQLDelight,
ktlint, and Square Wire run inside that process or as `JavaExec`. Prefab is the
pattern AGP already uses for a tool that also ships native builds: the artifact
is `com.google.prefab:cli` with classifier `all`, and the main class is
`com.google.prefab.cli.AppKt`. Signal's current tree uses Wire this way. An older
Signal tree exec'd `protoc`; the current one does not.

The init script sets every `JavaExec` executable to `$JAVA_HOME/bin/java`. A
plugin that resolves a tool with an `exe`, `linux-x86_64`, `osx-x86_64`, or
`windows-x86_64` classifier, when the same module also publishes a jar, takes
the jar. When the module publishes no jar, the build fails with that module's
name. That is the whole extension. It does not add an interpreter.

### A second layer, out of scope

`node` and `python` would attach at the hook that already exists: a child of
the daemon inherits `PATH` and `LD_PRELOAD`, so one new ELF on `$PREFIX/bin`
is visible to every Gradle `Exec`. Vivari is the shape of that ELF for Node.
Vendor the runtime's own `lib/`, shim only the platform binding, and leave npm
as upstream npm. The cascade is the next line of that runtime: native addons,
`node-gyp`, and the Python that `node-gyp` wants. A bionic CPython has the same
shape, with wheels instead of addons. Neither runtime is in this plan. The
attachment point is the PATH, and the work stops there.

`cargo` would not come along with either runtime. It is a compiler and a
standard library. `shadowsocks-android` is the app in the ten that needs it.
The Python it looks up is only the linker wrapper around that compiler.

## Phase 5 — distribution

The JDK, `sh`, cmake, ninja, make, aidl, and the NDK sysroot are first-run
downloads with pinned checksums, unpacked into app storage. They are not assets
in the APK. Pojav's split between a runtime APK and a no-runtime APK is the size
argument; AndroidIDE's `idesetup` is the installation shape, minus `apt` and
minus a package database.

The clang executables the editor runs stay inside `:samples:ndk-plugin`'s
`jniLibs`, as they do now. The Gradle-facing NDK tree is a second install of
the same bits into the standard layout. Sharing one copy on disk is a later
dedup, not a requirement for the first green build.

`docs/fdroid-izzyondroid.md` already records why official F-Droid cannot rebuild
this app (prebuilt aapt2, a build-time download, a non-free `android.jar`). A
downloaded JDK and a downloaded NDK sysroot are further anti-features of the
same kind. IzzyOnDroid remains the channel. Do not gate the work on removing
them.

OpenJDK is GPL-2.0 with the classpath exception. Termux's patches are part of
`termux-packages`. Shipping a binary we built from those patches is ordinary.
Copying AndroidIDE's Java tooling into this tree is unnecessary and would pin
us to a dead AGP model; this repo is already GPL-3.0-or-later, so the license
is not the reason to skip the copy. The reason is the frozen builder model.

## Tests that are not device tests

- The tooling jar, on the host JDK, initializes a fixture Gradle project and
  returns a model with the expected module and variant. No prefix.
- JDK selection: Gradle 8.6 with no toolchain maps to 17. A toolchain of 21 maps
  to the 21 image. `jvm.toolchain.vendor=jetbrains` still maps to that image and
  leaves auto-download off.
- NDK layout assembler: given the toolchain `out/` directory and a revision
  string, the function writes `ndk/<revision>/source.properties` with that
  `Pkg.Revision`, plus `toolchains/llvm/prebuilt/linux-x86_64/...`, `clang`, and
  the `clang++` symlink. The same helper, given a cmake version and a build-tools
  version, writes those alias directories onto one copied payload. A unit test
  can run this against a fake driver binary.
- The init script, applied to a fixture that sets four `abiFilters` and an ABI
  split, leaves `arm64-v8a` and records the removed names. A `JavaExec` task in
  that fixture has its executable set to the `java` from `JAVA_HOME`. A module
  that publishes both an `exe` classifier and a jar resolves the jar.
- Variant choice: a model with `defaultDebug` and `tvDebug` selects
  `assembleDefaultDebug`.
- `GradleImport` tests stay green. The static reader is still the pre-toolchain
  path.

## Order

Phase 0 and the `java -version` check come before any Gradle code is trusted.
Phase 1 and 2 land together as the first user-visible Gradle build (Java or
Kotlin, no native code): the two JDKs, the init script, the license file, and
the version aliases. Phase 3 deletes the native pipeline's registration only
after that build installs an APK. Phase 4 is the NDK, and it is blocked on
Phase 0's linker exec, not on a new sandbox. The compiler, the sysroot, and the
editor integration already exist. The arm64 filter and the full arm64 sysroot
are what make Phase 4 cover a CMake or `ndk-build` app that lists more than one
ABI.

## Ten Kotlin apps

Checked against this plan, not against the native pipeline. Counts are GitHub
stars and forks at the time of the check. Every repository is Kotlin and is well
above 100 of each. The question for each is whether `assemble` of its Android
application would run on the JDK, the wrapper's Gradle, the SDK platforms, and
the NDK tree described above.

Non-Android targets of the one multiplatform project (`open-ani/animeko`) are out
of scope. iOS, CocoaPods, desktop, and the JetBrains Runtime with JCEF are not
part of the verdict. Mihon applies `org.jetbrains.kotlin.multiplatform` inside
Android library modules; the same rule applies there. Success is the Android
assemble task.

Nine of the ten stay inside the binary table plus JAR tools. Their extra
generators are jars: desugaring, KSP, kapt, Hilt, data binding, Compose, Wire
(Signal; the current tree does not exec `protoc`), ktlint, SQLDelight and
moko-resources (Mihon), atomicfu (animeko). Prebuilt `.so` files in Signal,
Seal, v2rayNG, and animeko's `mediamp-ffmpeg` are packaged by AGP. They are not
compiled here. The one dep outside that set is below.

| App | Stars / forks | Android build | Verdict |
| --- | --- | --- | --- |
| `android/nowinandroid` | 21905 / 4673 | Gradle 9.7.1, AGP 9.3.2, Kotlin 2.3.0, minSdk 23, compileSdk 36, desugaring, Java 11 source. No NDK. | Yes. JDK 17, the wrapper's Gradle 9.7.1, platform 36. |
| `android/sunflower` | 17753 / 4766 | Gradle 8.6, AGP 8.4.0, Kotlin 2.0.0, KSP, Hilt, minSdk 24, compileSdk 34, Compose, data binding, buildConfig, Java 17. No NDK. | Yes. This is the old floor, and it matches JDK 17. An Unsplash key is read at configuration; a debug assemble still configures. |
| `skydoves/Pokedex` | 8364 / 1109 | Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, KSP and kapt, Hilt, minSdk 23, compileSdk 36. No NDK. | Yes. AGP 9.4 wants Gradle 9.6.0 or newer; the wrapper is 9.8.1. kapt is slow on a real JDK, not a missing tool. |
| `mihonapp/mihon` | 24230 / 1600 | Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, `com.android.kotlin.multiplatform.library`. minSdk 26, compileSdk 37.2, targetSdk 36, Java 17. `ndkVersion` is `29.0.14206865`. No `CMakeLists.txt`. | Yes for the Android app. Publish the synthetic NDK under `29.0.14206865` so AGP does not download Google's. Nothing in-tree compiles C, so the r27c clang is only there for strip. Non-Android KMP targets are not a goal. Platform `37.2` has to be downloadable. |
| `open-ani/animeko` | 20817 / 614 | The multiplatform project. Gradle 9.3.1, AGP 9.1.1, Kotlin 2.4.10, Compose Multiplatform. `android.compile.sdk=37`, `android.min.sdk=26`. `jvm.toolchain.version=21` and `jvm.toolchain.vendor=jetbrains`. | Yes for `:app:android`, task `assembleDefaultDebug`. See below. |
| `shadowsocks/shadowsocks-android` | 36805 / 11426 | Gradle 8.14.3. `externalNativeBuild.ndkBuild` on `src/main/jni/Android.mk`, default ABIs armeabi-v7a, arm64-v8a, x86, x86_64. Also `org.mozilla.rust-android-gradle` on `src/main/rust/shadowsocks-rust`, which needs `cargo` and `python` or `python3`. | No. This is a hard miss. |
| `signalapp/Signal-Android` | 29446 / 6898 | Gradle 9.6.0, AGP 9.4.0, compileSdk 37, minSdk 23, `ndkVersion` `28.0.13004108`. ABI splits for four ABIs plus a universal APK. `useLegacyPackaging`. No `CMakeLists.txt` and no `externalNativeBuild`. Native code arrives as prebuilt JNI (libsignal). Proto sources are compiled by Square Wire, a jar. | Yes. The init script narrows the splits to `arm64-v8a` and the assemble continues. The tree has to be installed at `28.0.13004108`. Platform 37. Heap is the practical risk. Wire needs no `protoc`. |
| `JunkFood02/Seal` | 29646 / 1466 | Gradle 8.10.2, Kotlin 2.0.20, KSP, compileSdk 35, minSdk 24, target 35. Flavor `publishChannel` / `generic`. `gradle.properties` sets `ABI_FILTERS=arm64-v8a`. Splits can still name four ABIs. `useLegacyPackaging`. No `externalNativeBuild`. | Yes. Prebuilt JNI, already aimed at arm64. Platform 35. One debug variant from the model. |
| `igorwojda/android-showcase` | 6807 / 908 | Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, KSP, convention plugins. Catalog: minSdk 28, compileSdk 36, targetSdk 35. No NDK. | Yes. Same shape as the other AGP 9.4 apps. JDK 17, platform 36. |
| `2dust/v2rayNG` | 63803 / 8262 | Wrapper at `V2rayNG/gradle/wrapper`, Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, minSdk 24, target 37, Java 17. `jniLibs.srcDirs("libs")` — prebuilt `.so` in the repo. ABI splits default to four ABIs plus universal; `ABI_FILTERS` can narrow. Flavors `fdroid` and `playstore`. Desugaring, Compose. | Yes. Splits narrow to arm64. The model picks one debug flavor. Platform 37. |

### animeko, Android only

iOS is off unless `local.properties` sets `ani.enable.ios=true` and
`ani.build.framework=true`. We do not set those. We do not run `podInstall`,
`buildDebugIpa`, or any desktop task. The Android client is
`app/android`, and CI builds it with `./gradlew assembleDefaultDebug`.

The default ABI list is `arm64-v8a`. `ani.android.abis=all` adds `armeabi-v7a`
and `x86_64`. Those filters package `libs.mediamp.ffmpeg`, a Maven prebuilt, with
`useLegacyPackaging` because FFmpeg is executed as a process. The init script
keeps `arm64-v8a` and drops the others, so an `all` checkout still assembles the
phone APK. There is no `CMakeLists.txt` in the repo. The variant rule picks
`defaultDebug` and does not also build the TV flavor. The default flavor raises
`minSdk` to at least 27. Signing configs are optional (`runCatching`).
`google-services` is `MissingGoogleServicesStrategy.IGNORE` unless
`ani.enable.firebase=true`; leave that unset.

`jvm.toolchain.vendor=jetbrains` and `jvm.toolchain.version=21` are satisfied by
our bionic JDK 21, with toolchain auto-download off. The "JBR must have JCEF"
note in `gradle.properties` is the desktop runtime. JCEF is out of scope with the
other non-Android targets. The project's `-Xmx6g` is overridden on the command
line, as Phase 1 requires. `org.gradle.configuration-cache=true` is legal on a
real Gradle; we do not strip it.

### shadowsocks, and why it stays a no

The arm64 filter would keep its `ndk-build` on `arm64-v8a`, and that half is the
NDK already in the binary table (`make`, clang). The build still stops in
`rust-android-gradle`. That plugin compiles Rust with `cargo`, then looks up
`python3` or `python` only to run `linker-wrapper.py` around the linker. The
Python is not a general build step. It disappears if `cargo` is absent, and
`cargo` is a compiler with its own standard library, not a JAR and not the Node
or Python runtime described above. Room and KSP in the same project are jars
and would run. The Rust objects are not a prebuilt we could package instead.

### What the ten forced the plan to change

- JDK 17 is legal for AGP 9 and for Sunflower. JDK 21 is for a 21 toolchain, which
  animeko requests.
- Platforms 34, 35, 36, 37, and 37.2 are downloaded on demand.
- The NDK directory name follows `ndkVersion` (Signal `28.0.13004108`, Mihon
  `29.0.14206865`, otherwise AGP 9's default r28c). The compiler inside a compile
  is still r27c. CMake and build-tools use the same alias.
- The APK we produce is arm64. A longer ABI list is narrowed, and the assemble
  continues. That is what makes a four-ABI CMake app buildable without a second
  LLVM backend.
- One debug variant is chosen from the model, so flavors do not multiply into a
  failing task.

## Out of scope

- PRoot, a guest Ubuntu or Alpine, qemu-user, and Nix-on-Droid's bootstrap.
- `dlopen` of `libjvm.so` into the IDE process.
- Baking a JDK or a Gradle distribution into the APK.
- Installing Termux's `gradle` package as the build entrypoint.
- Vendoring AndroidIDE's `tooling/builder-model-impl`.
- Finishing the ART `:build` daemon in `docs/build-process-isolation.md` for
  user builds.
- Non-Android targets of a Kotlin Multiplatform project: iOS, CocoaPods, desktop,
  and a JetBrains Runtime with JCEF. The Android application of that project is
  in scope.
- A userspace on `PATH`: Node and Vivari, Python, BusyBox applets other than
  `sh`, a native `protoc`, cargo (`shadowsocks/shadowsocks-android`), hermesc,
  the Flutter SDK, and Go. How a Node or Python runtime would attach is
  described under the build-system binaries, and it is not scheduled work.
- Further LLVM backends. The init script keeps the APK on `arm64-v8a`.
- A CMake implementation of our own. Projects with `CMakeLists.txt` are built
  by the real CMake. Projects with an `[ndk]` facet are built by
  `NdkBuildPlugin`.
