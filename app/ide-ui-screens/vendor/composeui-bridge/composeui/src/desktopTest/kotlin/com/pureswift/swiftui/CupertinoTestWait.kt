package com.pureswift.swiftui

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Compose Desktop 1.7.1 waitUntil builds a timeout message but never throws it.
 * Keep rendering through its normal clock, with an independently enforced deadline.
 */
internal fun ComposeTestRule.waitUntilBounded(
    timeoutMillis: Long = 5000,
    failureMessage: () -> String = { "Condition was not satisfied within ${timeoutMillis}ms" },
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    waitUntil(timeoutMillis) {
        val ready = condition()
        if (!ready && System.nanoTime() >= deadline) throw AssertionError(failureMessage())
        ready
    }
}
