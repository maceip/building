#!/usr/bin/env python3
"""Build a source SwiftUI view as a real ARM64 Android Cupertino preview.

Task checkouts/products stay in .build; Gradle uses its standard user cache.
No source project is rewritten.
"""
import argparse
from datetime import datetime, timezone
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


def prepare_native(bridge, package, app_sources, root, benchmark):
    for target in ["ComposeUI", "BridgeExport"]:
        shutil.copytree(bridge / "Sources" / target, package / "Sources" / target, dirs_exist_ok=True)
    # Minimal lifecycle addition: closing a pane drops the global root and
    # queued renders cannot republish it. Transport/evaluation stay upstream.
    runtime = package / "Sources/ComposeUI/BridgeRuntime.swift"
    text = runtime.read_text().replace("    /// Coalesces state changes", '''    public func stop() {
        host.onStateChange = nil
        if Self.current === self { Self.current = nil }
    }

    /// Coalesces state changes''').replace("guard let self else { return }", "guard let self, Self.current === self else { return }")
    write(runtime, text)
    copy(ROOT / "NativePreview.swift", app_sources / "NativePreview.swift")
    copy(ROOT / "SwiftUI.swift", package / "Sources/SwiftUI/SwiftUI.swift")
    write(app_sources / "Root.swift", f"import SwiftUI\nfunc makePreviewRoot() -> any View {{ {root}() }}\n")
    extra_products = extra_targets = ""
    if benchmark:
        copy(ROOT / "benchmark/NativeBenchmark.swift", app_sources / "NativeBenchmark.swift")
        for target in [app_sources, package / "Sources/JsonBaseline"]:
            copy(ROOT / "benchmark/Workload.swift", target / "Workload.swift")
        copy(ROOT / "benchmark/JsonBridge.swift", package / "Sources/JsonBaseline/JsonBridge.swift")
        write(package / "Sources/JsonBaseline/main.swift", '''import SwiftUI
try runPreview { benchmarkRoot(Int(CommandLine.arguments[1])!, CommandLine.arguments[2] == "leaf") }
''')
        extra_products = ', .executable(name: "JsonBaseline", targets: ["JsonBaseline"])'
        extra_targets = ', .executableTarget(name: "JsonBaseline", dependencies: ["SwiftUI"])'
    write(package / "Package.swift", '''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "AndroidSwiftUI", platforms: [.macOS(.v14)],
    products: [.library(name: "BuildingPreview", type: .dynamic, targets: ["BuildingPreview"])''' + extra_products + '''],
    dependencies: [
        .package(path: ''' + json.dumps(str(bridge / "SwiftUICore")) + '''),
        .package(url: "https://github.com/swiftlang/swift-java.git", revision: "b834a0dd18587134c17bcb7a7f16d23bc7ca8b8e")
    ],
    targets: [
        .target(name: "SwiftUI", dependencies: [.product(name: "SwiftUICore", package: "SwiftUICore")]),
        .target(name: "ComposeUI", dependencies: [.product(name: "SwiftUICore", package: "SwiftUICore"), .product(name: "SwiftJava", package: "swift-java")], swiftSettings: [.swiftLanguageMode(.v5)]),
        .target(name: "BridgeExport", dependencies: ["ComposeUI", .product(name: "SwiftJava", package: "swift-java")], exclude: ["swift-java.config"], swiftSettings: [.swiftLanguageMode(.v5)], plugins: [.plugin(name: "JExtractSwiftPlugin", package: "swift-java")]),
        .target(name: "BuildingPreview", dependencies: ["SwiftUI", "ComposeUI", "BridgeExport", .product(name: "SwiftJava", package: "swift-java")], swiftSettings: [.swiftLanguageMode(.v5)])
    ''' + extra_targets + '''])
''')
    if not (package / "Package.resolved").exists():
        pins = json.loads((bridge / "Package.resolved").read_text())
        names = {"swift-java", "swift-java-jni-core", "swift-syntax", "swift-argument-parser",
                 "swift-system", "swift-subprocess", "swift-collections", "swift-log"}
        pins["pins"] = [p for p in pins["pins"] if p["identity"] in names]
        write(package / "Package.resolved", json.dumps(pins, indent=2) + "\n")


def instrument_store(android):
    # Benchmark-only observation, after the actual Compose state assignment.
    # It receives the transported subtree, not a traversed copy of the root.
    path = android / "composeui/src/commonMain/kotlin/com/pureswift/swiftui/TreeStore.kt"
    text = path.read_text().replace("class TreeStore {", '''class TreeStore {
    var onMutation: ((String, ViewNode?) -> Unit)? = null''')
    text = text.replace("        root = node\n", '        root = node\n        onMutation?.invoke("full", node)\n')
    text = text.replace("        root = replaced\n", '        root = replaced\n        onMutation?.invoke("patch", node)\n')
    write(path, text)


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
    settings += '\nrootProject.name = "BuildingSwiftPreview"\ninclude(":composeui", ":preview", ":preview-app", ":swiftbridge", ":androidbridge")\n'
    for module in ["cupertino-core", "cupertino", "cupertino-native", "cupertino-adaptive", "cupertino-decompose", "cupertino-icons-extended"]:
        copy(bridge / "gradle/cupertino" / module / "build.gradle.kts", dest / module / "build.gradle.kts")
        settings += f'include(":{module}")\n'
    write(dest / "settings.gradle.kts", settings)
    # Restore the upstream renderer, generated callbacks and Android scheduler.
    for module in ["composeui", "androidbridge"]:
        shutil.copytree(bridge / module / "src", dest / module / "src", dirs_exist_ok=True)
        for name in ["build.gradle.kts", "consumer-rules.pro"]:
            copy(bridge / module / name, dest / module / name)
    write(dest / "swiftbridge/build.gradle.kts", '''plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets.main {
    java.srcDir(rootDir.resolve("../native/.build/checkouts/swift-java/SwiftKitCore/src/main/java"))
    java.srcDir(rootDir.resolve("../native/.build/plugins/outputs/native/BridgeExport/destination/JExtractSwiftPlugin/src/generated/java"))
    resources.srcDir(rootDir.resolve("../native/.build/checkouts/swift-java/SwiftKitCore/src/main/resources"))
}
''')
    common = dest / "composeui/src/commonMain/kotlin/com/pureswift/swiftui"
    copy(ROOT / "CupertinoPreview.kt", common / "CupertinoPreview.kt")
    render = common / "Render.kt"
    text = render.read_text()
    text = text.replace("private fun RenderResolved(node: ViewNode) {",
        "private fun RenderResolved(input: ViewNode) {\n    val node = previewStyle(input, LocalPreviewCupertino.current)")
    write(render, text)
    for module in ["preview", "preview-app"]:
        shutil.copytree(ROOT / "android" / module, dest / module, dirs_exist_ok=True)
    return dest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", type=Path, help="Swift view/model file; repeat for dependencies. Defaults to the bundled sample.")
    parser.add_argument("--root", default="ContentView", help="Zero-argument root View type")
    parser.add_argument("--github-counter", action="store_true", help="Clone and compile the pinned third-party SwiftUI counter, without editing it")
    parser.add_argument("--install", action="store_true", help="Install and launch on the selected ADB device")
    parser.add_argument("--benchmark", action="store_true", help="Include the opt-in JSON baseline and device benchmark fixtures")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.root):
        parser.error("--root must be a Swift type identifier")
    swift = Path(os.environ.get("SWIFT", str(Path.home() / "Library/Developer/Toolchains/swift-6.3.2-RELEASE.xctoolchain/usr/bin/swift")))
    if not swift.is_file():
        parser.error("Install Swift 6.3.2 or set SWIFT to the matching compiler executable")
    BUILD.mkdir(exist_ok=True)
    bridge = dependency()
    package = BUILD / "native"
    app_sources = package / "Sources/BuildingPreview"
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
    prepare_native(bridge, package, app_sources, args.root, args.benchmark)
    env = os.environ.copy()
    env["JAVA_HOME"] = run("/usr/libexec/java_home", "-v", "21", capture=True).strip()
    env["SWIFT_EXEC"] = str(swift.with_name("swiftc"))
    env["SWIFTKEY_SWIFT"] = str(swift)
    env["TOOLCHAINS"] = run("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier",
                            swift.parents[2] / "Info.plist", capture=True).strip()
    env["PATH"] = str(swift.parent) + os.pathsep + env["PATH"]
    env.setdefault("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")
    command = [swift, "build", "--package-path", package, "--disable-sandbox", "--skip-update", "-c", "release",
               "--swift-sdk", "aarch64-unknown-linux-android28"]
    run(*command, env=env)
    output = Path(run(*command, "--show-bin-path", capture=True, env=env).strip())
    android = prepare_gradle(bridge)
    benchmark_class = android / "preview/src/main/kotlin/dev/ide/swiftpreview/BenchmarkRuntime.kt"
    if args.benchmark:
        copy(ROOT / "benchmark/BenchmarkRuntime.kt", benchmark_class)
    else:
        benchmark_class.unlink(missing_ok=True)
    if not args.benchmark:
        shutil.rmtree(android / "preview-app/src/androidTest", ignore_errors=True)
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
    copy(output / "libBuildingPreview.so", native / "libBuildingPreview.so")
    copy(output / "libSwiftJava.so", native / "libSwiftJava.so")
    if args.benchmark:
        copy(output / "JsonBaseline", native / "libjson-baseline.so")
        instrument_store(android)

    sdk_path = Path.home() / ".swiftpm/swift-sdks" / (SDK + ".artifactbundle") / "swift-android"
    runtime = sdk_path / "swift-resources/usr/lib/swift-aarch64/android"
    # Ship the SDK's runtime libraries (never another app's compiled objects).
    for library in runtime.glob("*.so"):
        copy(library, native / library.name)
    cxx = sdk_path / "ndk-sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    copy(cxx, native / cxx.name)
    run(android / "gradlew", ":preview-app:assembleDebug", ":preview:assembleDebug", "--console=plain", cwd=android, env=env)
    apk = android / "preview-app/build/outputs/apk/debug/preview-app-debug.apk"
    receipt = {
        "built_at": datetime.now(timezone.utc).isoformat(),
        "bridge_repository": BRIDGE_URL, "bridge_revision": BRIDGE_REV,
        "source_repository": COUNTER_URL if args.github_counter else None,
        "source_revision": COUNTER_REV if args.github_counter else None,
        "source_sha256": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        "target": "aarch64-unknown-linux-android28", "compiler": run(swift, "--version", capture=True).strip(),
        "native_sha256": hashlib.sha256((output / "libBuildingPreview.so").read_bytes()).hexdigest(),
        "transport": "upstream typed JNI / subtree patches", "swift_configuration": "release",
        "benchmark_fixtures": args.benchmark,
        "swift_dependencies": json.loads((package / "Package.resolved").read_text())["pins"],
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
