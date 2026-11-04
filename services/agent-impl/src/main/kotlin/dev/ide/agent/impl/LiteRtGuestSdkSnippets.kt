package dev.ide.agent.impl

/**
 * Target guest-app stacks supported by the Self-Serve LiteRT-LM Corner building blocks generator.
 */
enum class LiteRtGuestLanguage(val id: String, val displayName: String, val relativeFilePath: String) {
    KOTLIN_ANDROID("kotlin", "Kotlin / Android", "src/main/kotlin/litert/LiteRtLocalClient.kt"),
    SWIFT("swift", "Swift / iOS", "Sources/LiteRtLocalClient.swift"),
    NODE_JS("node", "Node.js", "src/litertClient.mjs"),
    PYTHON("python", "Python", "litert_client.py"),
    ;

    companion object {
        fun fromId(raw: String?): LiteRtGuestLanguage =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: KOTLIN_ANDROID
    }
}

/**
 * Generates ready-to-compile guest-app local inference client building blocks so apps built and running on
 * the phone can immediately query the phone's installed `.litertlm` models via:
 * 1. Loopback OpenAI-compatible HTTP (`http://127.0.0.1:<port>/v1/chat/completions`)
 * 2. Loopback Native LiteRT-LM HTTP (`http://127.0.0.1:<port>/v1/litert/generate`)
 * 3. Android Zero-Copy ContentProvider IPC (`content://<authority>/models/<modelId>` + `ContentResolver.call`)
 */
object LiteRtGuestSdkSnippets {

    fun generateSnippet(
        language: LiteRtGuestLanguage,
        port: Int,
        modelId: String,
        delegate: LiteRtHardwareDelegate,
        ipcAuthority: String = LiteRtLmCatalog.DEFAULT_IPC_AUTHORITY,
    ): String {
        val effectiveModel = modelId.ifBlank { LiteRtLmCatalog.DEFAULT_MODEL_ID }
        val effectivePort = if (port > 0) port else LiteRtLmCatalog.DEFAULT_SERVER_PORT
        return when (language) {
            LiteRtGuestLanguage.KOTLIN_ANDROID -> kotlinAndroidSnippet(effectivePort, effectiveModel, delegate, ipcAuthority)
            LiteRtGuestLanguage.SWIFT -> swiftSnippet(effectivePort, effectiveModel, delegate)
            LiteRtGuestLanguage.NODE_JS -> nodeJsSnippet(effectivePort, effectiveModel, delegate)
            LiteRtGuestLanguage.PYTHON -> pythonSnippet(effectivePort, effectiveModel, delegate)
        }
    }

    private fun kotlinAndroidSnippet(
        port: Int,
        modelId: String,
        delegate: LiteRtHardwareDelegate,
        ipcAuthority: String,
    ): String = """
package litert

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-dependency on-device LiteRT-LM client for guest Android & JVM apps.
 * Connects to the phone's shared LiteRT-LM Corner without bundling multi-GB weights into your APK.
 */
object LiteRtLocalClient {
    const val DEFAULT_PORT: Int = $port
    const val DEFAULT_MODEL: String = "$modelId"
    const val DEFAULT_DELEGATE: String = "${delegate.id}"
    const val IPC_AUTHORITY: String = "$ipcAuthority"

    /**
     * Option 1: Loopback Native LiteRT-LM HTTP endpoint (`http://127.0.0.1:$port/v1/litert/generate`).
     */
    fun generateHttp(
        prompt: String,
        model: String = DEFAULT_MODEL,
        delegate: String = DEFAULT_DELEGATE,
        port: Int = DEFAULT_PORT,
    ): String {
        val conn = (URL("http://127.0.0.1:${'$'}port/v1/litert/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val payload = "{\"model\":\"${'$'}model\",\"delegate\":\"${'$'}delegate\",\"prompt\":\"${'$'}escapedPrompt\"}"
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Option 2: Zero-permission Android Binder IPC via ContentProvider (`content://$ipcAuthority`).
     */
    fun generateIpc(
        resolver: ContentResolver,
        prompt: String,
        model: String = DEFAULT_MODEL,
        delegate: String = DEFAULT_DELEGATE,
    ): String {
        val uri = Uri.parse("content://${'$'}IPC_AUTHORITY")
        val extras = Bundle().apply {
            putString("model", model)
            putString("delegate", delegate)
        }
        val reply = resolver.call(uri, "generate", prompt, extras)
            ?: error("LiteRT-LM Corner ContentProvider ($ipcAuthority) is unavailable.")
        if (!reply.getBoolean("ok", false)) {
            error(reply.getString("error") ?: "LiteRT-LM IPC generation failed.")
        }
        return reply.getString("text").orEmpty()
    }

    /**
     * Option 3: Open a read-only file descriptor to memory-map the phone's `.litertlm` artifact directly.
     */
    fun openModelDescriptor(
        resolver: ContentResolver,
        model: String = DEFAULT_MODEL,
    ): ParcelFileDescriptor? {
        val uri = Uri.parse("content://${'$'}IPC_AUTHORITY/models/${'$'}model")
        return resolver.openFileDescriptor(uri, "r")
    }
}
""".trimIndent()

    private fun swiftSnippet(
        port: Int,
        modelId: String,
        delegate: LiteRtHardwareDelegate,
    ): String = """
import Foundation

/// On-device LiteRT-LM client connecting to the local loopback server (`http://127.0.0.1:$port`).
public struct LiteRtLocalClient {
    public static let defaultEndpoint = URL(string: "http://127.0.0.1:$port/v1/litert/generate")!
    public static let openAiEndpoint = URL(string: "http://127.0.0.1:$port/v1/chat/completions")!
    public static let defaultModel = "$modelId"
    public static let defaultDelegate = "${delegate.id}"

    public static func generate(
        prompt: String,
        model: String = defaultModel,
        delegate: String = defaultDelegate
    ) async throws -> String {
        var request = URLRequest(url: defaultEndpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = [
            "model": model,
            "delegate": delegate,
            "prompt": prompt
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: request)
        if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
           let text = json["text"] as? String {
            return text
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
""".trimIndent()

    private fun nodeJsSnippet(
        port: Int,
        modelId: String,
        delegate: LiteRtHardwareDelegate,
    ): String = """
// On-device LiteRT-LM client for Node.js & Web guest apps (`http://127.0.0.1:$port`)
export const LITERT_BASE_URL = "http://127.0.0.1:$port";
export const DEFAULT_MODEL = "$modelId";
export const DEFAULT_DELEGATE = "${delegate.id}";

export async function generateLocal(prompt, { model = DEFAULT_MODEL, delegate = DEFAULT_DELEGATE } = {}) {
  const response = await fetch(`${'$'}{LITERT_BASE_URL}/v1/litert/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model, delegate, prompt }),
  });
  const payload = await response.json();
  if (!response.ok || payload.ok === false) {
    throw new Error(payload.error || `LiteRT-LM HTTP ${'$'}{response.status}`);
  }
  return payload.text;
}

export async function chatCompletionsLocal(messages, { model = DEFAULT_MODEL } = {}) {
  const response = await fetch(`${'$'}{LITERT_BASE_URL}/v1/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages, stream: false }),
  });
  return await response.json();
}
""".trimIndent()

    private fun pythonSnippet(
        port: Int,
        modelId: String,
        delegate: LiteRtHardwareDelegate,
    ): String = """
import json
import urllib.request

LITERT_BASE_URL = "http://127.0.0.1:$port"
DEFAULT_MODEL = "$modelId"
DEFAULT_DELEGATE = "${delegate.id}"


def generate_local(prompt: str, model: str = DEFAULT_MODEL, delegate: str = DEFAULT_DELEGATE) -> str:
    payload = json.dumps({"model": model, "delegate": delegate, "prompt": prompt}).encode("utf-8")
    req = urllib.request.Request(
        f"{LITERT_BASE_URL}/v1/litert/generate",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = json.loads(resp.read().decode("utf-8"))
        if not body.get("ok", False):
            raise RuntimeError(body.get("error", "LiteRT-LM generation failed"))
        return body.get("text", "")
""".trimIndent()
}
