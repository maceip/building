package dev.ide.android.reliability.repair

import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure task-policy tests; these do not claim to execute native instrumentation or install an APK. */
class ApprovedRepairPlanStoreTest {
    @Test fun acceptsOnlyExplicitLocalBuildAndUnitTaskForms() {
        listOf(
            ":classes" to ":test",
            ":app:assembleDebug" to ":app:testDebugUnitTest",
            ":native-client:app:assembleFreeDebug" to ":native-client:app:testFreeDebugUnitTest",
            ":app:compileDebugUnitTestKotlin" to ":app:testDebugUnitTest",
        ).forEach { (build, unit) -> ApprovedRepairPlanStore.validateLocalTasks(build, unit) }
    }

    @Test fun rejectsConnectedNativeAndAdbExecutionInEitherTaskField() {
        listOf(
            ":app:connectedDebugAndroidTest",
            ":app:connectedCheck",
            ":app:deviceDebugAndroidTest",
            ":app:pixelApi35DebugAndroidTest",
            ":app:testDebugAndroidTest",
            ":app:testConnectedDebugUnitTest",
            ":app:testDeviceDebugUnitTest",
            ":app:testInstrumentation",
            ":app:testInstrumentationUnitTest",
            ":app:testNative",
            ":app:testNativeUnitTest",
            ":app:testAdb",
            ":app:testADBUnitTest",
            ":app:assembleDebugAndroidTest",
            ":app:compileDebugAndroidTestKotlin",
            ":app:compileNativeTests",
            ":app:compileAdbTests",
            ":app:installDebug",
            ":app:uninstallAll",
            "adb shell am instrument -w dev.example.test/Runner",
            "/system/bin/am instrument -w dev.example.test/Runner",
        ).forEach { task ->
            assertThrows("Build task must not authorize $task", IllegalArgumentException::class.java) {
                ApprovedRepairPlanStore.validateLocalTasks(task, ":app:testDebugUnitTest")
            }
            assertThrows("Unit task must not authorize $task", IllegalArgumentException::class.java) {
                ApprovedRepairPlanStore.validateLocalTasks(":app:assembleDebug", task)
            }
        }
    }

    @Test fun rejectsTaskAbbreviationsArbitraryTestSuffixesAndCommandArguments() {
        listOf(
            "test", "app:test", ":app:tDU", ":app:testDebug", ":app:testCustom", ":app:testDebugUnitTest --tests Test",
            ":app:testDebugUnitTest :app:connectedDebugAndroidTest", ":app:testDebugUnitTest;adb", ":app:testDebugUnitTest\n",
        ).forEach { task ->
            assertThrows("Only an exact local unit task is accepted: $task", IllegalArgumentException::class.java) {
                ApprovedRepairPlanStore.validateLocalTasks(":app:assembleDebug", task)
            }
        }
        listOf("assembleDebug", ":app:aD", ":app:assembleDebug --offline", ":app:assembleDebug\n").forEach { task ->
            assertThrows("Only an exact local build task is accepted: $task", IllegalArgumentException::class.java) {
                ApprovedRepairPlanStore.validateLocalTasks(task, ":app:testDebugUnitTest")
            }
        }
    }
}
