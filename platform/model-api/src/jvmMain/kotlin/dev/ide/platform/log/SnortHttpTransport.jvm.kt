// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.log

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val snortHttpExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "snort-http-io").apply { isDaemon = true }
}

internal actual fun postJsonToSnort(url: String, jsonPayload: String): Boolean =
    try {
        snortHttpExecutor.submit(
            Callable {
                val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1_500
                    readTimeout = 2_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val bytes = jsonPayload.encodeToByteArray()
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            },
        ).get(2_500, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {
        false
    }

internal actual fun appendLineToFile(path: String, line: String) {
    runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").encodeToByteArray())
        }
    }
}
