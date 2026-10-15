package dev.ide.gradle

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/**
 * Install only app-owned command wrappers, never into a user's project or Gradle prefix. The
 * interpreter starts with Gradle's linker environment; exports below affect only this child,
 * and the final exec loads Node with its own runtime prefix/preloads. Do not link the runtime's
 * entire bin directory onto Gradle's PATH: it would also replace Java's shell/native toolchain.
 */
object GradleRuntimeCommandBridge {
    @Synchronized
    fun install(
        directory: Path,
        interpreter: Path,
        node: Path,
        npmCli: Path,
        runtimeEnvironment: Map<String, String>,
        extraNodeCommands: Map<String, Path> = emptyMap(),
    ): GradleScriptEnvironment {
        require(directory.isAbsolute && interpreter.isAbsolute && node.isAbsolute && npmCli.isAbsolute)
        require(interpreter.toString().none { it.isWhitespace() || it == '\u0000' }) { "Invalid bridge interpreter" }
        require(Files.isRegularFile(interpreter)) { "The Gradle script interpreter is not installed" }
        require(Files.isRegularFile(node) && Files.isRegularFile(npmCli)) { "Bundled Node/npm is required for Gradle scripts" }
        require(!runtimeEnvironment["PATH"].isNullOrBlank()) { "The bundled runtime has no command search path" }
        runtimeEnvironment.forEach { (key, value) ->
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key) && '\u0000' !in value) { "Invalid runtime environment entry" }
        }
        // APK-owned real executable avoids making /proc/self/exe identify linker64 (Node fork).
        val executable = node.toRealPath().toString()
        val commands = linkedMapOf("node" to listOf(executable), "npm" to listOf(executable, npmCli.toString()))
        val npx = npmCli.resolveSibling("npx-cli.js")
        if (Files.isRegularFile(npx)) commands["npx"] = listOf(executable, npx.toString())
        extraNodeCommands.toSortedMap().forEach { (name, entrypoint) ->
            require(name.matches(Regex("[a-z][a-z0-9-]*")) && name !in commands) { "Invalid extra runtime command" }
            require(entrypoint.isAbsolute && Files.isRegularFile(entrypoint)) { "Bundled runtime command is missing: $name" }
            commands[name] = listOf(executable, entrypoint.toString())
        }
        val scripts = commands.mapValues { (_, command) -> script(interpreter, command, runtimeEnvironment) }
        // Immutable location: concurrent executions and APK updates never see half-rewritten scripts.
        val digest = MessageDigest.getInstance("SHA-256")
        scripts.forEach { (name, content) ->
            digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            digest.update(content.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
        }
        val identity = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        val bin = directory.resolve(identity).resolve("bin")
        Files.createDirectories(bin)
        scripts.forEach { (name, content) -> writeExecutable(bin.resolve(name), content) }
        return GradleScriptEnvironment(listOf(bin))
    }

    private fun script(interpreter: Path, command: List<String>, environment: Map<String, String>): String = buildString {
        append("#!").append(interpreter).append('\n')
        append("# App-owned Node/npm bridge. Only this child changes runtime prefix/linker state.\n")
        for ((key, value) in environment.toSortedMap()) {
            if (key == "PATH") {
                // Runtime npm-global/bin, headers and tools remain authoritative; explicit task PATH
                // additions remain available after them. No expansion/evaluation of runtime values.
                append("export PATH=").append(quote(value)).append("\"\${PATH:+:\$PATH}\"\n")
            } else if (isDefault(key)) {
                // Respect the caller's home/temp (especially repair's private home) and explicit npm
                // options. Header/compiler/shell locations are NOT defaults: they must match this pack.
                append("if [ \"\${").append(key).append("+x}\" != x ]; then export ")
                    .append(key).append('=').append(quote(value)).append("; fi\n")
            } else {
                append("export ").append(key).append('=').append(quote(value)).append('\n')
            }
        }
        append("exec ").append(command.joinToString(" ", transform = ::quote)).append(" \"\$@\"\n")
    }

    private fun isDefault(key: String): Boolean = key in setOf("HOME", "TMPDIR", "LANG", "TERM", "NO_COLOR") ||
        (key.startsWith("npm_config_") && key !in setOf("npm_config_nodedir", "npm_config_python", "npm_config_script_shell"))

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun writeExecutable(path: Path, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.isExecutable(path) && Files.readAllBytes(path).contentEquals(bytes)) return
        val temporary = Files.createTempFile(path.parent, ".bridge-", ".tmp")
        try {
            Files.write(temporary, bytes)
            check(temporary.toFile().setExecutable(true, true)) { "Cannot make the bundled runtime bridge executable" }
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
