#!/usr/bin/env python3
"""Outside installer for authorized disposable fixtures; run by the device-owning coordinator only.

Example (one physical device, existing APK outputs):
  python3 samples/reliability-fixture/testtools/run_fixture.py --serial 10.0.0.157:38627 \
      --authorize-fixture-reset --with-ide-controller
Use --build to build the four target APKs plus ONE canonical instrumentation APK first.
Two-device proof is a separate gate: --mode backup --serial SOURCE --second-serial DESTINATION.
No emulator substitution, shell identity spoofing, arbitrary target packages, or custom report format.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Iterable

PACKAGE = "dev.ide.reliability.fixture"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
IDE_RUNNER = "com.tyron.code.dev.test/androidx.test.runner.AndroidJUnitRunner"
FIXTURE = "dev.ide.reliability.fixture."
CONTROLLER = "dev.ide.android.reliability.fixture.FixtureProcessAcceptanceTest"
REPRO = FIXTURE + "StateAcceptanceTest#draftAndSelectionSurviveRecreateAndBackground"
FUNCTIONAL = FIXTURE + "FixtureFunctionalTest#editorStartsAndSavedRecordRoundTrips"
SEED = FIXTURE + "MigrationAcceptanceTest#seedBeforePackageReplacement"
VERIFY = FIXTURE + "MigrationAcceptanceTest#userDataSurvivesPackageReplacement"
NETWORK = FIXTURE + "NetworkAcceptanceTest#realClientFaultsNeverCommitFalseSuccessAndSdkCapturesRequestPairs"
SDK = FIXTURE + "SdkCaptureAcceptanceTest#realLifecycleAndLocalExportPreserveDurablePendingIdentity"
BACKUP = FIXTURE + "BackupAcceptanceTest#twoLogicalDatasetsRestoreRecordsAttachmentsSettingsAndRollback"
EXPORT = FIXTURE + "BackupAcceptanceTest#exportForPhysicalTransfer"
IMPORT = FIXTURE + "BackupAcceptanceTest#importFromPhysicalTransfer"
PROCESS = CONTROLLER + "#draftCheckpointSurvivesForceStopAndRelaunch"
CRASH = CONTROLLER + "#uncaughtCrashInSeparateUidIsJournaledBeforeAndroidTerminatesProcess"
FILTERS = {REPRO, FUNCTIONAL, SEED, VERIFY, NETWORK, SDK, BACKUP, EXPORT, IMPORT, PROCESS, CRASH}
VARIANTS = ("brokenV1Debug", "brokenV2Debug", "fixedV1Debug", "fixedV2Debug")
TEST_VARIANT = "fixedV2DebugAndroidTest"
TRANSFER = "files/fixture-transfer/physical.backup"


class Blocked(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args: list[str], *, input_bytes: bytes | None = None, timeout: int = 120) -> bytes:
    completed = subprocess.run(args, input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=timeout, check=False)
    if completed.returncode:
        # Deliberately omit command arguments: Gradle may carry an embedded test-build pairing nonce.
        raise RuntimeError(f"Command exited {completed.returncode}: "
                           + completed.stderr.decode("utf-8", "replace")[-4000:]
                           + completed.stdout.decode("utf-8", "replace")[-4000:])
    return completed.stdout


@dataclass(frozen=True)
class PhysicalDevice:
    serial: str
    hardware_serial: str
    api: int
    model: str


def require_distinct_devices(source: PhysicalDevice, target: PhysicalDevice) -> None:
    if source.serial == target.serial or source.hardware_serial == target.hardware_serial:
        raise Blocked("Two serial aliases of one physical device are not cross-device evidence")


class Device:
    def __init__(self, adb: Path, serial: str):
        if not serial or re.search(r"\s", serial) or serial.startswith("-"):
            raise ValueError("An explicit ADB serial is required")
        self.adb, self.serial = str(adb), serial

    def run(self, *args: str, input_bytes: bytes | None = None, timeout: int = 120) -> bytes:
        return command([self.adb, "-s", self.serial, *args], input_bytes=input_bytes, timeout=timeout)

    def shell(self, *args: str, input_bytes: bytes | None = None, timeout: int = 120) -> str:
        return self.run("shell", "-T", shlex.join(args), input_bytes=input_bytes, timeout=timeout).decode("utf-8", "replace")

    def physical(self) -> PhysicalDevice:
        if self.run("get-state").strip() != b"device":
            raise Blocked("Selected ADB endpoint is not an authorized online device")
        def prop(name: str) -> str:
            return self.shell("getprop", name).strip()
        api, hardware, model = int(prop("ro.build.version.sdk")), prop("ro.hardware"), prop("ro.product.model")
        if api not in (35, 37):
            raise Blocked(f"Physical API35/API37 gate required; selected device is API{api}")
        if (prop("ro.kernel.qemu") == "1" or prop("ro.boot.qemu") == "1"
                or re.search(r"goldfish|ranchu|emulator|sdk_gphone", hardware + " " + model, re.I)):
            raise Blocked("Emulators are not evidence for this physical-device gate")
        hardware_serial = prop("ro.boot.serialno") or prop("ro.serialno")
        if not hardware_serial or hardware_serial.lower() == "unknown":
            raise Blocked("Cannot establish distinct physical-device identity")
        return PhysicalDevice(self.serial, hardware_serial, api, model)

    def reset_fixture(self, apk: Path) -> None:
        # `pm path` returns exit 1 when absent, which is not a shell failure for this precondition.
        installed = self.shell("pm", "list", "packages", PACKAGE).splitlines()
        if "package:" + PACKAGE in installed:
            self.run("uninstall", PACKAGE)
        self.install(apk, replace=False)

    def install(self, apk: Path, *, replace: bool = True, downgrade: bool = False) -> None:
        args = ["install", "-t"]
        if replace:
            args.append("-r")
        if downgrade:
            args.append("-d")
        output = self.run(*args, str(apk), timeout=180).decode("utf-8", "replace")
        if "Success" not in output:
            raise RuntimeError("Fixture installation did not confirm success: " + output)

    def version(self, expected: int) -> None:
        output = self.shell("dumpsys", "package", PACKAGE)
        actual = re.search(r"\bversionCode=(\d+)", output)
        if actual is None or int(actual[1]) != expected:
            raise AssertionError("Installed fixture version did not match actual replacement APK")

    def instrument(self, test: str, *, expect_failure: str | None = None,
                   physical_transfer: str | None = None) -> str:
        if test not in FILTERS:
            raise ValueError("Only frozen fixture acceptance test filters may be executed")
        args = ["am", "instrument", "-w", "-r", "-e", "class", test]
        if test.startswith(CONTROLLER):
            args += ["-e", "authorizedFixture", PACKAGE]
            runner = IDE_RUNNER
        else:
            runner = RUNNER
        if physical_transfer is not None:
            if (test, physical_transfer) not in ((EXPORT, "export"), (IMPORT, "import")):
                raise ValueError("Physical transfer authorization is scoped to its exact test")
            args += ["-e", "physicalTransfer", physical_transfer]
        output = self.shell(*args, runner, timeout=240)
        require_test_result(output, test, expect_failure)
        print(("EXPECTED-FAIL " if expect_failure else "PASS ") + test, flush=True)
        return output


def require_test_result(output: str, test: str, expected_failure: str | None = None) -> None:
    """A launch failure, skipped test, process crash or no-test run is NEVER an expected regression."""
    target_class, target_method = test.split("#")
    records: list[tuple[int, dict[str, str]]] = []
    record: dict[str, str] = {}
    current_key: str | None = None
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field = line.removeprefix("INSTRUMENTATION_STATUS: ")
            if "=" in field:
                current_key, value = field.split("=", 1)
                record[current_key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            records.append((int(line.rsplit(" ", 1)[1]), record))
            record, current_key = {}, None
        elif current_key:
            record[current_key] += "\n" + line
    terminal = [(code, fields) for code, fields in records if code != 1
                and fields.get("class") == target_class and fields.get("test") == target_method]
    if (len(terminal) != 1 or "INSTRUMENTATION_CODE: -1" not in output
            or "INSTRUMENTATION_FAILED" in output or "Process crashed" in output):
        raise AssertionError("Instrumentation did not complete the frozen test exactly once:\n" + output[-12000:])
    code, fields = terminal[0]
    if expected_failure is None:
        if code != 0 or re.search(r"OK \(1 tests?\)", output) is None:
            raise AssertionError("Acceptance did not pass (skips are not passes):\n" + output[-12000:])
    elif (code != -2 or expected_failure not in fields.get("stack", "")
          or not re.search(r"AssertionError|ComparisonFailure", fields.get("stack", ""))):
        raise AssertionError("Broken fixture did not fail the required assertion:\n" + output[-12000:])


def find_artifact(module: Path, variant: str) -> Path:
    candidates: list[Path] = []
    outputs = (module / "build/outputs/apk").resolve()
    for metadata in outputs.rglob("output-metadata.json"):
        model = json.loads(metadata.read_text())
        if model.get("variantName") != variant:
            continue
        elements = model.get("elements", [])
        if len(elements) != 1 or elements[0].get("filters"):
            raise RuntimeError("Fixture requires one universal APK, not split artifacts")
        apk = (metadata.parent / elements[0]["outputFile"]).resolve()
        if not apk.is_relative_to(outputs) or not apk.is_file():
            raise RuntimeError("APK metadata points outside this fixture's output directory")
        candidates.append(apk)
    if len(candidates) != 1:
        raise Blocked(f"Expected exactly one {variant} APK; run the authorized --build first")
    return candidates[0]


def latest_tool(sdk: Path, name: str) -> Path:
    candidates = [path for path in (sdk / "build-tools").glob("*/" + name) if path.is_file()]
    if not candidates:
        raise Blocked(f"Installed Android SDK has no {name}; coordinator must provision it")
    return sorted(candidates, key=lambda path: [int(v) for v in re.findall(r"\d+", path.parent.name)])[-1]


def verify_artifacts(apks: dict[str, Path], test_apk: Path, sdk: Path) -> None:
    signer, aapt = latest_tool(sdk, "apksigner"), latest_tool(sdk, "aapt2")
    certificates: set[str] = set()
    for variant, apk in list(apks.items()) + [(TEST_VARIANT, test_apk)]:
        signed = command([str(signer), "verify", "--print-certs", str(apk)]).decode()
        digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signed)
        if len(digests) != 1:
            raise AssertionError("Fixture must have one verified signing certificate")
        certificates.add(digests[0].lower())
        badging = command([str(aapt), "dump", "badging", str(apk)]).decode()
        expected_package = TEST_PACKAGE if variant == TEST_VARIANT else PACKAGE
        package = re.search(r"package: name='([^']+)' versionCode='(\d*)'", badging)
        if package is None or package[1] != expected_package:
            raise AssertionError("Refusing to install anything outside the authorized fixture packages")
        if variant != TEST_VARIANT and (not package[2] or int(package[2]) != (1 if "V1" in variant else 2)):
            raise AssertionError("Real APK versionCode must differ for upgrade/downgrade")
        manifest = command([str(aapt), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"]).decode()
        if variant != TEST_VARIANT:
            if "application-debuggable" not in badging or not re.search(r"android:testOnly[^\n]*(?:0xffffffff|true)", manifest):
                raise AssertionError("Only testOnly+debuggable target APKs may be installed")
        elif ("E: instrumentation" not in manifest
              or re.search(r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + '"', manifest) is None
              or '="androidx.test.runner.AndroidJUnitRunner"' not in manifest):
            raise AssertionError("Canonical instrumentation APK must target only the authorized fixture")
    if len(certificates) != 1:
        raise AssertionError("All replacement APKs and the one test APK must have exactly the same signer")
    if len({sha256(apk) for apk in apks.values()}) != len(apks):
        raise AssertionError("Broken/fixed/v1/v2 must be genuinely distinct APK files")


class FrozenAssertions:
    def __init__(self, module: Path, test_apk: Path):
        controller = module.parents[1] / "app/ide-android/src/androidTest/kotlin/dev/ide/android/reliability/fixture/FixtureProcessAcceptanceTest.kt"
        self.paths = sorted((module / "src/androidTest").rglob("*.java")) + [controller, test_apk]
        if len(self.paths) < 2:
            raise Blocked("Frozen instrumentation sources or canonical APK are missing")
        self.expected = {path: sha256(path) for path in self.paths}
        self.test_apk = test_apk
        print("FROZEN test APK SHA256 " + self.expected[test_apk], flush=True)

    def verify(self) -> None:
        if any(not path.is_file() or sha256(path) != digest for path, digest in self.expected.items()):
            raise AssertionError("Instrumentation assertions/APK changed during before/after execution")


def run_faults(device: Device, apks: dict[str, Path], frozen: FrozenAssertions, with_controller: bool) -> None:
    physical = device.physical()
    print(f"PHYSICAL {physical.model} API{physical.api} serial={physical.serial}", flush=True)
    for behavior in ("broken", "fixed"):
        frozen.verify()
        device.reset_fixture(apks[behavior + "V2Debug"])
        device.install(frozen.test_apk)
        device.version(2)
        device.instrument(FUNCTIONAL)
        device.instrument(REPRO, expect_failure="Unsaved draft lost during real Activity recreation" if behavior == "broken" else None)
        if with_controller:
            device.instrument(PROCESS, expect_failure="Persisted checkpoint lost draft after process death" if behavior == "broken" else None)
            device.instrument(CRASH)
        if behavior == "fixed":
            for test in (NETWORK, SDK, BACKUP):
                frozen.verify(); device.instrument(test)
        # Real same-package/certificate replacement. Never uninstall or clear between seed and verify.
        device.reset_fixture(apks[behavior + "V1Debug"])
        device.install(frozen.test_apk)
        device.version(1); device.instrument(SEED)
        device.install(apks[behavior + "V2Debug"]); device.version(2)
        device.instrument(VERIFY, expect_failure="Installer must provide real old data" if behavior == "broken" else None)
        device.instrument(SEED)
        device.install(apks[behavior + "V1Debug"], downgrade=True); device.version(1)
        device.instrument(VERIFY, expect_failure="Installer must provide real old data" if behavior == "broken" else None)
        frozen.verify()
    print("PASS native fixture before/after matrix with unchanged instrumentation APK", flush=True)
    if not with_controller:
        print("BLOCKED process-kill/crash gate: IDE controller was not requested/installed", flush=True)
    print("BLOCKED cross-device backup gate: run separate --mode backup with a second physical device", flush=True)


def run_backup(source: Device, target: Device, apk: Path, frozen: FrozenAssertions) -> None:
    require_distinct_devices(source.physical(), target.physical())
    for device in (source, target):
        frozen.verify(); device.reset_fixture(apk); device.install(frozen.test_apk)
    source.instrument(EXPORT, physical_transfer="export")
    archive = source.run("exec-out", "run-as", PACKAGE, "cat", TRANSFER)
    if not 64 < len(archive) <= 64 * 1024 * 1024:
        raise AssertionError("Exported selected-user-data archive has invalid size")
    target.shell("run-as", PACKAGE, "mkdir", "-p", "files/fixture-transfer")
    # Stdin goes only to this exact fixture-private file, never an arbitrary package or path.
    target.shell("run-as", PACKAGE, "sh", "-c", "umask 077; cat > " + TRANSFER, input_bytes=archive)
    received = target.run("exec-out", "run-as", PACKAGE, "cat", TRANSFER)
    if hashlib.sha256(archive).digest() != hashlib.sha256(received).digest():
        raise AssertionError("Encrypted archive changed during the actual two-device transfer")
    target.instrument(IMPORT, physical_transfer="import")
    frozen.verify()
    print("PASS distinct physical-device portable backup records/attachments/settings", flush=True)


def main(argv: Iterable[str] | None = None) -> int:
    module = Path(__file__).resolve().parents[1]
    root = module.parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit authorized physical ADB serial; never auto-discovered")
    parser.add_argument("--second-serial")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--authorize-fixture-reset", action="store_true", help="Allow install/uninstall/reset of disposable fixture ONLY")
    parser.add_argument("--mode", choices=("faults", "backup"), default="faults")
    parser.add_argument("--build", action="store_true", help="Coordinator-only: build fixtures using repository Gradle")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Additional non-secret Gradle flag; pairing builds are separate")
    parser.add_argument("--with-ide-controller", action="store_true", help="IDE/test APK must already be installed by coordinator")
    args = parser.parse_args(argv)
    if not args.authorize_fixture_reset:
        raise Blocked("Pass --authorize-fixture-reset to authorize changes only to disposable fixture packages")
    if args.mode == "backup" and not args.second_serial:
        raise Blocked("Second physical device unavailable; two logical databases are not cross-device proof")
    if any("reliability.fixture." in flag for flag in args.gradle_arg):
        raise Blocked("Pairing builds are separate from the unpaired before/after matrix; use a private config file")
    if args.build:
        tasks = [":samples:reliability-fixture:assemble" + variant[0].upper() + variant[1:]
                 for variant in (*VARIANTS, TEST_VARIANT)]
        output = command([str(root / "gradlew"), "-p", str(root), "--console=plain", *args.gradle_arg, *tasks], timeout=900)
        print(output.decode("utf-8", "replace"), flush=True)
    apks = {variant: find_artifact(module, variant) for variant in VARIANTS}
    test_apk = find_artifact(module, TEST_VARIANT)
    verify_artifacts(apks, test_apk, args.sdk)
    frozen = FrozenAssertions(module, test_apk)
    adb = args.adb or args.sdk / "platform-tools/adb"
    source = Device(adb, args.serial)
    if args.mode == "backup":
        run_backup(source, Device(adb, args.second_serial), apks["fixedV2Debug"], frozen)
    else:
        run_faults(source, apks, frozen, args.with_ide_controller)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Blocked as error:
        print("BLOCKED " + str(error), file=sys.stderr)
        sys.exit(3)
    except (AssertionError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
