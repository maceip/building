import importlib.util
from pathlib import Path
import sys
import unittest

SPEC = importlib.util.spec_from_file_location("run_fixture", Path(__file__).with_name("run_fixture.py"))
fixture = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = fixture
SPEC.loader.exec_module(fixture)


def result(code=0, stack="", summary="OK (1 test)"):
    clazz, method = fixture.REPRO.split("#")
    return (f"INSTRUMENTATION_STATUS: class={clazz}\n"
            f"INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: class={clazz}\nINSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: stack={stack}\nINSTRUMENTATION_STATUS_CODE: {code}\n"
            f"INSTRUMENTATION_RESULT: stream=\n{summary}\nINSTRUMENTATION_CODE: -1\n")


class FixtureHarnessTest(unittest.TestCase):
    def test_only_actual_assertion_failure_can_satisfy_broken_fixture(self):
        fixture.require_test_result(result(), fixture.REPRO)
        failure = "org.junit.ComparisonFailure: Unsaved draft lost during real Activity recreation"
        fixture.require_test_result(result(-2, failure, "FAILURES!!!"), fixture.REPRO, "Unsaved draft lost")
        for output in (
            result(-3, "AssumptionViolatedException: missing device"),
            result(-2, "java.lang.SecurityException: permission denied", "FAILURES!!!"),
            "INSTRUMENTATION_FAILED: runner unavailable\nINSTRUMENTATION_CODE: -1\n",
            "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
            result(-2, failure, "Process crashed"),
        ):
            with self.subTest(output=output), self.assertRaises(AssertionError):
                fixture.require_test_result(output, fixture.REPRO, "Unsaved draft lost")
        with self.assertRaises(AssertionError):
            fixture.require_test_result(result(-3), fixture.REPRO)

    def test_same_physical_device_alias_is_not_cross_device_proof(self):
        source = fixture.PhysicalDevice("10.0.0.157:38627", "one-phone", 37, "Pixel")
        alias = fixture.PhysicalDevice("USB-SERIAL", "one-phone", 37, "Pixel")
        with self.assertRaises(fixture.Blocked):
            fixture.require_distinct_devices(source, alias)
        fixture.require_distinct_devices(source, fixture.PhysicalDevice("second", "two-phone", 35, "Pixel"))
        with self.assertRaises(ValueError):
            fixture.Device(Path("adb"), "")


if __name__ == "__main__":
    unittest.main()
