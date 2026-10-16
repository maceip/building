#!/usr/bin/env python3
"""Exercise the compiled sample's state through the actual process transport."""
import argparse
import json
import selectors
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("executable")
    args = parser.parse_args()
    child = subprocess.Popen([args.executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, bufsize=1)
    ready = selectors.DefaultSelector()
    ready.register(child.stdout, selectors.EVENT_READ)

    def read():
        assert ready.select(10), "Swift did not emit a tree within 10 seconds"
        line = child.stdout.readline()
        assert line, "Swift stopped before returning a tree"
        return json.loads(line)

    def nodes(tree):
        yield tree
        for sub in tree["children"]:
            yield from nodes(sub)

    def text(tree):
        return [n["props"].get("text") for n in nodes(tree) if n["type"] == "Text"]

    def control(tree, kind, index=0):
        return [n for n in nodes(tree) if n["type"] == kind][index]

    def send(node, key, kind, value=None):
        child.stdin.write(json.dumps(dict(id=str(node["props"][key]), kind=kind, value=value)) + "\n")
        child.stdin.flush()
        return read()

    try:
        tree = read()
        assert "Hello, World" in text(tree) and "0" in text(tree)
        tree = send(control(tree, "Button"), "onTap", "void")
        assert "2" in text(tree), text(tree)
        tree = send(control(tree, "Toggle"), "onChange", "bool", False)
        assert "Add 1" in text(tree)
        tree = send(control(tree, "Button"), "onTap", "void")
        assert "3" in text(tree), text(tree)
        tree = send(control(tree, "TextField"), "onChange", "string", "Android 👋")
        assert "Hello, Android 👋" in text(tree), text(tree)
        tree = send(control(tree, "Button", 1), "onTap", "void")
        assert "0" in text(tree)
        child.stdin.close()
        assert child.wait(timeout=10) == 0
        print("PASS: initial tree, Swift counter, toggle, Unicode text binding, reset, clean EOF")
    finally:
        ready.close()
        if child.poll() is None:
            child.kill()
            child.wait()

    invalid = subprocess.Popen([args.executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True)
    try:
        # Waiting for the initial tree also proves process startup succeeded.
        ready = selectors.DefaultSelector()
        ready.register(invalid.stdout, selectors.EVENT_READ)
        assert ready.select(10), "Swift did not start for the invalid-event check"
        json.loads(invalid.stdout.readline())
        _, error = invalid.communicate('{"id":"999999999","kind":"void"}\n', timeout=10)
        assert invalid.returncode != 0 and "invalidEvent" in error
        print("PASS: unknown callback is a visible failure")
    finally:
        ready.close()
        if invalid.poll() is None:
            invalid.kill()
            invalid.wait()


if __name__ == "__main__":
    main()
