package fragments.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class AcceptedPatchTest {
    @Test
    fun `accepted patch renumbers later hunk after an earlier rejection`() {
        val old = (1..30).map { "line $it" }
        val proposed = old.toMutableList().apply {
            this[2] = "changed three"
            this[24] = "changed twenty five"
        }
        val file = ContentDiff.parse(ContentFileChange("app.kt",
            oldContent = old.joinToString("\n", postfix = "\n"),
            newContent = proposed.joinToString("\n", postfix = "\n")))
        assertEquals(2, file.hunks.size)
        val patch = AcceptedPatch.format(listOf(file), setOf(file.hunks[1].id))
        assertEquals(proposed.mapIndexed { index, value -> if (index == 2) old[index] else value }
            .joinToString("\n", postfix = "\n"),
            AcceptedPatch.applyToContent(file, setOf(file.hunks[1].id),
                old.joinToString("\n", postfix = "\n"), proposed.joinToString("\n", postfix = "\n")))
        assertFalse("changed three" in patch)
        assertTrue("changed twenty five" in patch)
        val accepted = DiffParser.parse(patch).single().hunks.single()
        assertEquals(file.hunks[1].oldStart, accepted.oldStart)
        assertEquals(file.hunks[1].oldStart, accepted.newStart)
        assertEquals(proposed.mapIndexed { index, value -> if (index == 2) old[index] else value },
            apply(old, accepted))
        assertEquals("", AcceptedPatch.format(listOf(file), emptySet()))
        gitApply(patch, mapOf("app.kt" to old.joinToString("\n", postfix = "\n"))) { directory ->
            assertEquals(proposed.mapIndexed { index, value -> if (index == 2) old[index] else value }
                .joinToString("\n", postfix = "\n"), Files.readString(directory.resolve("app.kt")).replace("\r\n", "\n"))
        }
    }

    @Test
    fun `accepted patch preserves create delete binary and final newline markers`() {
        val added = ContentDiff.parse(ContentFileChange("new.txt", oldContent = "", newContent = "hello\n"))
        val deleted = ContentDiff.parse(ContentFileChange("gone.txt", oldContent = "bye\n", newContent = ""))
        val newline = ContentDiff.parse(ContentFileChange("line.txt", oldContent = "old", newContent = "new\n"))
        val binary = ContentDiff.parse(ContentFileChange("image.png", oldContent = "old\u0000", newContent = "new\u0000"))
        val patch = AcceptedPatch.format(listOf(added, deleted, newline, binary), setOf(
            added.hunks.single().id, deleted.hunks.single().id, newline.hunks.single().id,
            "${binary.id}:file"))
        assertEquals("hello\n", AcceptedPatch.applyToContent(added, setOf(added.hunks.single().id), "", "hello\n"))
        assertEquals("", AcceptedPatch.applyToContent(deleted, setOf(deleted.hunks.single().id), "bye\n", ""))
        assertEquals("new\n", AcceptedPatch.applyToContent(newline, setOf(newline.hunks.single().id), "old", "new\n"))
        assertEquals("old\u0000", AcceptedPatch.applyToContent(binary, emptySet(), "old\u0000", "new\u0000"))
        assertEquals("new\u0000", AcceptedPatch.applyToContent(binary, setOf("${binary.id}:file"), "old\u0000", "new\u0000"))
        assertTrue("new file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,1 @@" in patch)
        assertTrue("deleted file mode 100644\n--- a/gone.txt\n+++ /dev/null\n@@ -1,1 +0,0 @@" in patch)
        assertTrue("\\ No newline at end of file" in patch)
        assertTrue("Binary files a/image.png and b/image.png differ" in patch)
        val textOnly = AcceptedPatch.format(listOf(added, deleted), setOf(
            added.hunks.single().id, deleted.hunks.single().id))
        gitApply(textOnly, mapOf("gone.txt" to "bye\n")) { directory ->
            assertEquals("hello\n", Files.readString(directory.resolve("new.txt")).replace("\r\n", "\n"))
            assertFalse(Files.exists(directory.resolve("gone.txt")))
        }
    }

    @Test
    fun `all accepted subsets of separated replace insert and delete hunks apply cleanly`() {
        val old = (1..50).map { "line $it" }
        val proposed = old.toMutableList().apply {
            removeAt(35)
            add(21, "inserted after twenty one")
            this[5] = "changed six"
        }
        val file = ContentDiff.parse(ContentFileChange("mixed.txt",
            oldContent = old.joinToString("\n", postfix = "\n"),
            newContent = proposed.joinToString("\n", postfix = "\n")))
        assertEquals(3, file.hunks.size)
        for (mask in 1..7) {
            val accepted = file.hunks.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val patch = AcceptedPatch.format(listOf(file), accepted.map { it.id }.toSet())
            val expected = applyAll(old, accepted).joinToString("\n", postfix = "\n")
            assertEquals(expected, AcceptedPatch.applyToContent(file, accepted.map { it.id }.toSet(),
                old.joinToString("\n", postfix = "\n"), proposed.joinToString("\n", postfix = "\n")),
                "Applied content for accepted hunk mask $mask")
            gitApply(patch, mapOf("mixed.txt" to old.joinToString("\n", postfix = "\n"))) { directory ->
                assertEquals(expected, Files.readString(directory.resolve("mixed.txt")).replace("\r\n", "\n"),
                    "Accepted hunk mask $mask")
            }
        }
    }

    private fun apply(old: List<String>, hunk: DiffHunk): List<String> {
        val rebuilt = old.take(if (hunk.oldStart == 0) 0 else hunk.oldStart - 1).toMutableList()
        var cursor = rebuilt.size
        hunk.lines.forEach { line -> when (line.type) {
            DiffLineType.Context -> { assertEquals(old[cursor], line.text); rebuilt += line.text; cursor++ }
            DiffLineType.Deleted -> { assertEquals(old[cursor], line.text); cursor++ }
            DiffLineType.Added -> rebuilt += line.text
            DiffLineType.HunkHeader -> error("Unexpected hunk header")
        } }
        rebuilt += old.drop(cursor)
        return rebuilt
    }

    private fun applyAll(old: List<String>, hunks: List<DiffHunk>): List<String> {
        val rebuilt = mutableListOf<String>()
        var cursor = 0
        hunks.forEach { hunk ->
            val start = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
            rebuilt += old.subList(cursor, start)
            cursor = start
            hunk.lines.forEach { line -> when (line.type) {
                DiffLineType.Context -> { assertEquals(old[cursor], line.text); rebuilt += line.text; cursor++ }
                DiffLineType.Deleted -> { assertEquals(old[cursor], line.text); cursor++ }
                DiffLineType.Added -> rebuilt += line.text
                DiffLineType.HunkHeader -> error("Unexpected hunk header")
            } }
        }
        rebuilt += old.drop(cursor)
        return rebuilt
    }

    private fun gitApply(patch: String, initialFiles: Map<String, String>, verify: (java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("accepted-patch-")
        try {
            initialFiles.forEach { (path, content) -> Files.writeString(directory.resolve(path), content) }
            val patchFile = directory.resolve("accepted.patch")
            Files.writeString(patchFile, patch)
            fun git(vararg args: String) {
                val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")} failed: $output\n$patch")
            }
            git("init", "-q")
            git("apply", "--check", patchFile.toString())
            git("apply", patchFile.toString())
            verify(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
