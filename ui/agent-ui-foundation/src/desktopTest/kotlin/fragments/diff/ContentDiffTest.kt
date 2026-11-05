package fragments.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

class ContentDiffTest {
    @Test
    fun `last line newline change is visible`() {
        val file = ContentDiff.parse(ContentFileChange("app.kt", oldContent = "same", newContent = "same\n"))
        val lines = file.hunks.single().lines
        assertEquals(listOf(DiffLineType.Deleted, DiffLineType.Added), lines.map { it.type })
        assertTrue(lines.first().noNewline)
        assertTrue(!lines.last().noNewline)
    }

    @Test
    fun `patch marker preserves missing newline`() {
        val patch = """diff --git a/app.kt b/app.kt
--- a/app.kt
+++ b/app.kt
@@ -1 +1 @@
-old
\ No newline at end of file
+new
"""
        val file = ContentDiff.parse(ContentFileChange("app.kt", patch = patch))
        assertTrue(file.hunks.single().lines.first().noNewline)
    }

    @Test
    fun `patch input retains added file status`() {
        val patch = """diff --git a/new.kt b/new.kt
--- /dev/null
+++ b/new.kt
@@ -0,0 +1 @@
+first
"""
        val file = ContentDiff.parse(ContentFileChange("new.kt", patch = patch))
        assertEquals(FileChangeStatus.Added, file.status)
        assertEquals("first", file.hunks.single().lines.single().text)
    }

    @Test
    fun `empty new file retains added status`() {
        val file = ContentDiff.parse(ContentFileChange("empty.kt", oldContent = "", newContent = ""))
        assertEquals(FileChangeStatus.Added, file.status)
        assertTrue(file.hunks.isEmpty())
    }

    @Test
    fun `generated hunks replay repeated-line edits without changing untouched lines`() {
        val random = Random(41)
        repeat(250) { case ->
            val old = List(random.nextInt(0, 24)) { "token ${random.nextInt(0, 7)}" }
            val new = List(random.nextInt(0, 24)) { "token ${random.nextInt(0, 7)}" }
            val file = ContentDiff.parse(ContentFileChange("case-$case.kt",
                oldContent = old.joinToString("\n", postfix = if (old.isEmpty()) "" else "\n"),
                newContent = new.joinToString("\n", postfix = if (new.isEmpty()) "" else "\n")))
            val rebuilt = mutableListOf<String>()
            var cursor = 0
            file.hunks.forEach { hunk ->
                val start = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
                rebuilt += old.subList(cursor, start)
                cursor = start
                hunk.lines.forEach { line ->
                    when (line.type) {
                        DiffLineType.Context -> {
                            assertEquals(old[cursor], line.text, "case $case context")
                            rebuilt += line.text
                            cursor++
                        }
                        DiffLineType.Deleted -> {
                            assertEquals(old[cursor], line.text, "case $case deletion")
                            cursor++
                        }
                        DiffLineType.Added -> rebuilt += line.text
                        DiffLineType.HunkHeader -> error("Unexpected hunk header line")
                    }
                }
            }
            rebuilt += old.drop(cursor)
            assertEquals(new, rebuilt, "case $case")
        }
    }

    @Test
    fun `content edits keep separate hunks and line numbers`() {
        val old = (1..30).map { "line $it" }
        val new = old.toMutableList().apply {
            this[2] = "changed three"
            this[24] = "changed twenty five"
        }
        val file = ContentDiff.parse(ContentFileChange("app.kt",
            oldContent = old.joinToString("\n", postfix = "\n"),
            newContent = new.joinToString("\n", postfix = "\n")))
        assertEquals(2, file.hunks.size)
        assertEquals(listOf("app.kt:hunk_0", "app.kt:hunk_1"), file.hunks.map { it.id })
        assertEquals(listOf("line 3", "line 25"), file.hunks.flatMap { it.lines }
            .filter { it.type == DiffLineType.Deleted }.map { it.text })
        assertEquals(listOf("changed three", "changed twenty five"), file.hunks.flatMap { it.lines }
            .filter { it.type == DiffLineType.Added }.map { it.text })
        assertEquals(listOf(3, 25), file.hunks.flatMap { it.lines }
            .filter { it.type == DiffLineType.Added }.map { it.newLineNumber })
    }

    @Test
    fun `insertions deletions repeated paths and binary changes are distinct`() {
        val files = ContentDiff.parseAll(listOf(
            ContentFileChange("same.kt", oldContent = "", newContent = "one\n"),
            ContentFileChange("same.kt", oldContent = "two\n", newContent = ""),
            ContentFileChange("asset.png", binary = true),
            ContentFileChange("renamed.kt", oldPath = "before.kt", oldContent = "same\n", newContent = "same\n"),
        ))
        assertEquals(listOf("same.kt", "same.kt#2", "asset.png", "renamed.kt"), files.map { it.id })
        assertEquals(FileChangeStatus.Added, files[0].status)
        assertEquals(FileChangeStatus.Deleted, files[1].status)
        assertEquals(FileChangeStatus.Renamed, files[3].status)
        assertEquals(0, files[0].hunks.single().oldCount)
        assertEquals(0, files[1].hunks.single().newCount)
        assertTrue(files[2].hunks.isEmpty())
        assertTrue(files[3].hunks.isEmpty())
    }

    @Test
    fun `large changed middle becomes one bounded replacement`() {
        val old = (1..1_100).joinToString("\n", postfix = "\n") { "old $it" }
        val new = (1..1_100).joinToString("\n", postfix = "\n") { "new $it" }
        val file = ContentDiff.parse(ContentFileChange("large.kt", oldContent = old, newContent = new))
        assertEquals(1, file.hunks.size)
        assertEquals(1_100, file.hunks.single().deletionsCount)
        assertEquals(1_100, file.hunks.single().additionsCount)
    }
}
