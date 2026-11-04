// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.log

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

@OptIn(ExperimentalForeignApi::class)
internal actual fun postJsonToSnort(url: String, jsonPayload: String): Boolean =
    try {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        val req = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setValue("application/json", forHTTPHeaderField = "Content-Type")
            val body: NSData? = NSString.create(string = jsonPayload).dataUsingEncoding(NSUTF8StringEncoding)
            setHTTPBody(body)
        }
        NSURLSession.sharedSession.dataTaskWithRequest(req).resume()
        true
    } catch (_: Throwable) {
        false
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun appendLineToFile(path: String, line: String) {
    val fp = fopen(path, "a") ?: return
    try {
        fputs(line + "\n", fp)
    } finally {
        fclose(fp)
    }
}
