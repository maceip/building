#!/usr/bin/env python3
"""Run plain-JVM VT parser tests and real host JNI PTY tests without Gradle or a device."""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP = ROOT / "app/ide-android"


def cached(pattern):
    matches = list((Path.home() / ".gradle/caches/modules-2/files-2.1").glob(pattern))
    if not matches:
        raise RuntimeError(f"Dependency not in Gradle cache: {pattern}")
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, default=Path.home() / "Library/Android/sdk/platforms/android-36.1/android.jar")
    args = parser.parse_args()
    junit = cached("junit/junit/4.13.2/*/*.jar")
    hamcrest = cached("org.hamcrest/hamcrest-core/1.3/*/*.jar")
    java_home = Path(os.environ.get("JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home"], text=True).strip()) if sys.platform == "darwin" else Path(shutil.which("javac")).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="building-terminal-tests-") as temporary:
        work = Path(temporary)
        platform = "darwin" if sys.platform == "darwin" else "linux"
        library = work / ("libruntime-pty.dylib" if platform == "darwin" else "libruntime-pty.so")
        subprocess.run(["cc", "-std=c11", "-O2", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror",
                        f"-I{java_home}/include", f"-I{java_home}/include/{platform}", str(HERE / "pty.c"), "-o", str(library)], check=True)
        sources = list((APP / "vendor/termux-terminal/src/com/termux/terminal").glob("*.java"))
        sources += [APP / "src/terminal/java/dev/ide/android/workbench/terminal/RuntimePty.java",
                    APP / "src/terminalTest/java/dev/ide/android/workbench/terminal/TerminalParserTest.java", HERE / "RuntimePtyHostTest.java"]
        subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, [args.android_jar, junit])), "-d", str(work), *map(str, sources)], check=True)
        subprocess.run([str(java_home / "bin/java"), f"-Djava.library.path={work}", "-cp", os.pathsep.join(map(str, [work, junit, hamcrest])),
                        "org.junit.runner.JUnitCore", "dev.ide.android.workbench.terminal.TerminalParserTest", "dev.ide.android.workbench.terminal.RuntimePtyHostTest"], check=True)


if __name__ == "__main__":
    main()
