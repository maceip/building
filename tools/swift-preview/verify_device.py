#!/usr/bin/env python3
"""Interact with the installed preview APK over ADB; save real device evidence."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "ai.building.swiftpreview"


def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], text=not binary)


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/building-swift-preview.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/building-swift-preview.xml")).iter("node"))


def wait_for(predicate, description):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        nodes = ui()
        match = next((n for n in nodes if predicate(n)), None)
        if match is not None:
            return match
        time.sleep(.3)
    raise AssertionError(f"Missing {description}; visible texts: {[n.get('text') for n in nodes]}")


def text(value):
    return wait_for(lambda n: n.get("text") == value, repr(value))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=["sample", "github"], default="sample")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / ".build/evidence")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/dev.ide.swiftpreview.PreviewActivity")
    if args.case == "sample":
        text("Hello, World")
        tap(text("Add 2"))
        text("2")
        tap(wait_for(lambda n: n.get("checkable") == "true" and n.get("package") == PACKAGE, "Cupertino switch"))
        tap(text("Add 1"))
        text("3")
        tap(wait_for(lambda n: n.get("class") == "android.widget.EditText", "Cupertino text field"))
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(5):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", "Android")
        text("Hello, Android")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        text("3")
    else:
        text("Value: 0")
        tap(wait_for(lambda n: n.get("content-desc") == "Increment", "increment icon"))
        text("Value: 1")
        tap(text("−"))
        text("Value: 0")
        tap(wait_for(lambda n: n.get("content-desc") == "Increment", "increment icon"))
        text("Value: 1")

    time.sleep(.5)
    screenshot = args.output / f"{args.case}-android.png"
    screenshot.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    (args.output / f"{args.case}-ui.xml").write_text(adb("shell", "cat", "/sdcard/building-swift-preview.xml"))
    processes = adb("shell", "ps", "-A", "-o", "PID,ARGS")
    assert "libbuilding-preview.so" in processes, "No Swift child is running on Android"
    if args.case == "sample":
        tap(text("Reset"))
        text("0")
    # Back destroys the activity/composition; its Swift child must exit too.
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    deadline = time.monotonic() + 10
    while "libbuilding-preview.so" in adb("shell", "ps", "-A", "-o", "PID,ARGS"):
        assert time.monotonic() < deadline, "Swift process survived closing its view"
        time.sleep(.2)
    receipt = dict(case=args.case, device=adb("shell", "getprop", "ro.product.model").strip(),
                   abi=adb("shell", "getprop", "ro.product.cpu.abi").strip(),
                   sdk=adb("shell", "getprop", "ro.build.version.sdk").strip(),
                   interactions="passed", swift_child="observed", dispose="child exited",
                   screenshot=str(screenshot.resolve()))
    installed = adb("shell", "pm", "path", PACKAGE).strip().splitlines()[0].removeprefix("package:")
    receipt["installed_apk_sha256"] = adb("shell", "sha256sum", installed).split()[0]
    (args.output / f"{args.case}-device-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
