#!/usr/bin/env python3
"""Build a source SwiftUI view as a real ARM64 Android Cupertino preview.

Task checkouts/products stay in .build; Gradle uses its standard user cache.
No source project is rewritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / ".build"
BRIDGE_REV = "9bbcc0bc008d43fa3e204fe5856cd41cc03f4d7c"
BRIDGE_URL = "https://github.com/maceip/Swiftkey.git"
SDK = "swift-6.3.2-RELEASE_android"
COUNTER_URL = "https://github.com/takenori-kabeya/SwiftUIStateManagementBook-Counter.git"
COUNTER_REV = "05bb80af3d0700eb926bf4031da50729ab1d78f3"


def run(*args, cwd=None, capture=False, env=None):
    print("+", " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def dependency():
    checkout = BUILD / "bridge"
    if not checkout.exists():
        run("git", "clone", "--filter=blob:none", "--no-checkout", BRIDGE_URL, checkout)
        run("git", "sparse-checkout", "set", "AndroidSwiftUI", cwd=checkout)
        run("git", "checkout", "--detach", BRIDGE_REV, cwd=checkout)
    actual = run("git", "rev-parse", "HEAD", cwd=checkout, capture=True).strip()
    if actual != BRIDGE_REV or run("git", "status", "--porcelain", cwd=checkout, capture=True).strip():
        raise SystemExit("Cached bridge differs from the pinned clean revision; use a fresh .build/bridge.")
    return checkout / "AndroidSwiftUI"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def prepare_gradle(bridge):
    dest = BUILD / "android"
    dest.mkdir(parents=True, exist_ok=True)
    for name in ["gradlew", "gradlew.bat", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
                 "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]:
        copy(bridge / name, dest / name)
    vendor = dest / "vendor"
    if not vendor.exists():
        vendor.symlink_to(bridge / "vendor", target_is_directory=True)
    settings = (bridge / "settings.gradle.kts").read_text().split('rootProject.name =')[0]
    settings += '\nrootProject.name = "BuildingSwiftPreview"\ninclude(":composeui", ":preview", ":preview-app")\n'
    for module in ["cupertino-core", "cupertino", "cupertino-native", "cupertino-adaptive", "cupertino-decompose", "cupertino-icons-extended"]:
        copy(bridge / "gradle/cupertino" / module / "build.gradle.kts", dest / module / "build.gradle.kts")
        settings += f'include(":{module}")\n'
    write(dest / "settings.gradle.kts", settings)
    # Use the existing renderer unchanged. Only its JNI host is replaced with
    # the process transport, so this build needs no app-specific SwiftKey code.
    compose = dest / "composeui"
    source = compose / "src"
    if not source.exists():
        shutil.copytree(bridge / "composeui/src", source,
                        ignore=shutil.ignore_patterns("desktopTest", "SwiftUIHostView.kt"))
    copy(bridge / "composeui/consumer-rules.pro", compose / "consumer-rules.pro")
    script = (bridge / "composeui/build.gradle.kts").read_text()
    script = script.replace('            implementation(project(":androidbridge"))', '')
    start = script.index('        val jvmShared by creating {')
    end = script.index('        val desktopTest by getting {', start)
    script = script[:start] + script[end:]
    write(compose / "build.gradle.kts", script)
    for module in ["preview", "preview-app"]:
        shutil.copytree(ROOT / "android" / module, dest / module, dirs_exist_ok=True)
    return dest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", type=Path, help="Swift view/model file; repeat for dependencies. Defaults to the bundled sample.")
    parser.add_argument("--root", default="ContentView", help="Zero-argument root View type")
    parser.add_argument("--github-counter", action="store_true", help="Clone and compile the pinned third-party SwiftUI counter, without editing it")
    parser.add_argument("--install", action="store_true", help="Install and launch on the selected ADB device")
    parser.add_argument("--host-only", action="store_true", help="Compile the same shim for macOS for protocol tests")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.root):
        parser.error("--root must be a Swift type identifier")
    swift = Path(os.environ.get("SWIFT", str(Path.home() / "Library/Developer/Toolchains/swift-6.3.2-RELEASE.xctoolchain/usr/bin/swift")))
    if not swift.is_file():
        parser.error("Install Swift 6.3.2 or set SWIFT to the matching compiler executable")
    BUILD.mkdir(exist_ok=True)
    bridge = dependency()
    package = BUILD / "swift"
    app_sources = package / "Sources/PreviewApp"
    if app_sources.exists():
        shutil.rmtree(app_sources)
    app_sources.mkdir(parents=True)
    sources = args.source or [ROOT / "sample/ContentView.swift"]
    if args.github_counter:
        if args.source:
            parser.error("Choose --github-counter or --source")
        counter = BUILD / "github-counter"
        if not counter.exists():
            run("git", "clone", "--no-checkout", COUNTER_URL, counter)
            run("git", "checkout", "--detach", COUNTER_REV, cwd=counter)
        if run("git", "rev-parse", "HEAD", cwd=counter, capture=True).strip() != COUNTER_REV:
            parser.error("GitHub counter cache is at a different revision")
        if run("git", "status", "--porcelain", cwd=counter, capture=True).strip():
            parser.error("GitHub counter cache has changes; refusing to claim an unchanged upstream build")
        sources = [counter / "SimpleCounterSwiftUI/SimpleCounterSwiftUI/ContentView.swift"]
    for i, source in enumerate(sources):
        copy(source.resolve(), app_sources / f"Input{i}.swift")
    copy(ROOT / "Bridge.swift", app_sources / "Bridge.swift")
    copy(ROOT / "SwiftUI.swift", package / "Sources/SwiftUI/SwiftUI.swift")
    write(app_sources / "main.swift", f"import SwiftUI\ntry runPreview {{ {args.root}() }}\n")
    write(package / "Package.swift", '''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "BuildingPreview", platforms: [.macOS(.v14)],
    products: [.executable(name: "PreviewApp", targets: ["PreviewApp"])],
    dependencies: [.package(path: ''' + json.dumps(str(bridge / "SwiftUICore")) + ''')],
    targets: [.target(name: "SwiftUI", dependencies: [.product(name: "SwiftUICore", package: "SwiftUICore")]),
              .executableTarget(name: "PreviewApp", dependencies: ["SwiftUI"])])
''')
    command = [swift, "build", "--package-path", package, "--disable-sandbox", "-c", "debug"]
    if not args.host_only:
        command += ["--swift-sdk", "aarch64-unknown-linux-android28"]
    run(*command)
    output = Path(run(*command, "--show-bin-path", capture=True).strip())
    if args.host_only:
        print(output / "PreviewApp")
        return
    android = prepare_gradle(bridge)
    write(android / "preview-app/src/main/assets/preview-title.txt",
          "GitHub counter" if args.github_counter else "Swift preview")
    notices = android / "preview-app/src/main/assets/notices"
    if notices.exists():
        shutil.rmtree(notices)
    for path in [bridge / "LICENSE", bridge / "vendor/compose-cupertino/LICENSE.txt"]:
        if path.is_file():
            copy(path, notices / ("compose-cupertino.txt" if "vendor" in path.parts else "AndroidSwiftUI.txt"))
    if args.github_counter:
        copy(BUILD / "github-counter/LICENSE", notices / "github-counter.txt")
    native = android / "preview-app/src/main/jniLibs/arm64-v8a"
    if native.exists():
        shutil.rmtree(native)
    native.mkdir(parents=True)
    copy(output / "PreviewApp", native / "libbuilding-preview.so")
    sdk_path = Path.home() / ".swiftpm/swift-sdks" / (SDK + ".artifactbundle") / "swift-android"
    runtime = sdk_path / "swift-resources/usr/lib/swift-aarch64/android"
    # Ship the SDK's runtime libraries (never another app's compiled objects).
    for library in runtime.glob("*.so"):
        copy(library, native / library.name)
    cxx = sdk_path / "ndk-sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    copy(cxx, native / cxx.name)
    env = os.environ.copy()
    env.setdefault("JAVA_HOME", run("/usr/libexec/java_home", "-v", "21", capture=True).strip())
    env.setdefault("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")
    run(android / "gradlew", ":preview-app:assembleDebug", ":preview:assembleDebug", "--console=plain", cwd=android, env=env)
    apk = android / "preview-app/build/outputs/apk/debug/preview-app-debug.apk"
    receipt = {
        "bridge_repository": BRIDGE_URL, "bridge_revision": BRIDGE_REV,
        "source_repository": COUNTER_URL if args.github_counter else None,
        "source_revision": COUNTER_REV if args.github_counter else None,
        "source_sha256": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        "target": "aarch64-unknown-linux-android28", "compiler": run(swift, "--version", capture=True).strip(),
        "executable_sha256": hashlib.sha256((output / "PreviewApp").read_bytes()).hexdigest(),
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "device_execution_verified": False,
    }
    write(BUILD / "build-receipt.json", json.dumps(receipt, indent=2) + "\n")
    print(f"APK: {apk}")
    print(f"Build receipt (not a device-test receipt): {BUILD / 'build-receipt.json'}")
    if args.install:
        run("adb", "install", "-r", apk)
        run("adb", "shell", "am", "force-stop", "ai.building.swiftpreview")
        run("adb", "shell", "am", "start", "-n", "ai.building.swiftpreview/dev.ide.swiftpreview.PreviewActivity")


if __name__ == "__main__":
    main()
