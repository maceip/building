#!/usr/bin/env python3
"""
Dual-Track E2E Test Suite Orchestrator & Runner
Executes all 4 Tiers across both pipelines:
- Tier 1: Feature Coverage
- Tier 2: Boundary & Corner Cases
- Tier 3: Cross-Feature Combinations
- Tier 4: Real-World Application Scenarios
"""

import sys
import time
import unittest
from pathlib import Path

# Add tests/e2e to sys.path
TEST_DIR = Path(__file__).parent.resolve()
if str(TEST_DIR) not in sys.path:
    sys.path.insert(0, str(TEST_DIR))

import test_tier1_features
import test_tier2_boundaries
import test_tier3_combinations
import test_tier4_real_world

def main():
    print("=" * 80)
    print("  DUAL-TRACK COMPILER & LIVE GUI E2E TEST SUITE RUNNER")
    print("=" * 80)
    print(f"Target Working Directory: /Users/mac/building")
    print(f"Pipelines Covered:")
    print(f"  1. Android-on-iOS (:android-apk, :interp-compose, :ide-ios)")
    print(f"  2. iOS-on-Android (:ide-core, Swiftc, ViewHost JSON bridge)")
    print("=" * 80)

    loader = unittest.TestLoader()
    suite = unittest.TestSuite()

    tiers = [
        ("Tier 1: Feature Coverage", test_tier1_features.Tier1FeatureCoverageTest),
        ("Tier 2: Boundary & Corner Cases", test_tier2_boundaries.Tier2BoundaryCasesTest),
        ("Tier 3: Cross-Feature Combinations", test_tier3_combinations.Tier3CrossCombinationsTest),
        ("Tier 4: Real-World Application Scenarios", test_tier4_real_world.Tier4RealWorldScenarioTest),
    ]

    total_tests = 0
    start_time = time.time()
    all_passed = True

    for name, test_class in tiers:
        print(f"\n>>> Running {name}...")
        tier_suite = loader.loadTestsFromTestCase(test_class)
        runner = unittest.TextTestRunner(verbosity=2)
        result = runner.run(tier_suite)
        total_tests += result.testsRun
        if not result.wasSuccessful():
            all_passed = False
            print(f"❌ {name} FAILED!")
        else:
            print(f"✅ {name} PASSED ({result.testsRun} tests)")

    elapsed = time.time() - start_time
    print("\n" + "=" * 80)
    print(f"  E2E TEST SUITE SUMMARY: {'PASSED' if all_passed else 'FAILED'}")
    print(f"  Total Test Cases Executed: {total_tests}")
    print(f"  Duration: {elapsed:.2f} seconds")
    print("=" * 80)

    sys.exit(0 if all_passed else 1)

if __name__ == "__main__":
    main()
