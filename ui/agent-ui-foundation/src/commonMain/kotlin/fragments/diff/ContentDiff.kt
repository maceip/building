package fragments.diff

import androidx.compose.runtime.Immutable

/** One proposed file edit. Supply contents, or a unified patch when contents are unavailable. */
@Immutable
data class ContentFileChange(
    val path: String,
    val oldPath: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
    val patch: String? = null,
    val language: String? = null,
    val binary: Boolean? = null,
)

/** Produces stable hunk and line identities for review from file contents. */
object ContentDiff {
    private const val ContextLines = 3
    private const val MaxMiddleLines = 2_000

    fun parseAll(changes: List<ContentFileChange>): List<FileDiffState> {
        val used = mutableSetOf<String>()
        val nextSuffix = mutableMapOf<String, Int>()
        return changes.map { change ->
            val base = change.path
            var id = base
            if (id in used) {
                var suffix = nextSuffix[base] ?: 2
                while ("$base#$suffix" in used || changes.any { it.path == "$base#$suffix" }) suffix++
                id = "$base#$suffix"
                nextSuffix[base] = suffix + 1
            }
            used += id
            parse(change, id)
        }
    }

    fun parse(change: ContentFileChange, id: String = change.path): FileDiffState {
        val old = change.oldContent ?: ""
        val new = change.newContent ?: ""
        val parsedPatch = if (change.oldContent == null && change.newContent == null && change.patch != null)
            DiffParser.parse(change.patch).firstOrNull() else null
        val oldPath = change.oldPath ?: parsedPatch?.oldPath
        val status = when {
            oldPath != null && oldPath != change.path -> FileChangeStatus.Renamed
            parsedPatch != null -> parsedPatch.status
            change.oldContent == "" || (change.oldContent == null && change.patch == null) -> FileChangeStatus.Added
            change.newContent == "" || (change.newContent == null && change.patch == null) -> FileChangeStatus.Deleted
            else -> FileChangeStatus.Modified
        }
        val binary = change.binary ?: (old.take(8_000).contains('\u0000') || new.take(8_000).contains('\u0000') ||
            change.patch?.contains("GIT binary patch") == true ||
            change.patch?.contains(Regex("(?m)^Binary files .* differ$")) == true)
        val hunks = when {
            binary -> emptyList()
            parsedPatch != null ->
                parsedPatch.hunks.mapIndexed { index, hunk ->
                    hunk.copy(id = "$id:hunk_$index", lines = hunk.lines.mapIndexed { lineIndex, line ->
                        line.copy(id = "$id:hunk_$index:line_$lineIndex")
                    })
                }
            else -> contentHunks(old, new, id)
        }
        return FileDiffState(id = id, oldPath = oldPath, newPath = change.path,
            status = status, hunks = hunks, originalContent = old, currentContent = new,
            isBinary = binary)
    }

    private enum class Kind { Equal, Delete, Add }
    private data class SourceLine(val text: String, val ended: Boolean)
    private data class Edit(val kind: Kind, val line: SourceLine) {
        val text: String get() = line.text
    }

    private fun lines(content: String): List<SourceLine> = when {
        content.isEmpty() -> emptyList()
        content.endsWith('\n') -> content.dropLast(1).split('\n').map { SourceLine(it, true) }
        else -> content.split('\n').let { parts -> parts.mapIndexed { index, text ->
            SourceLine(text, index < parts.lastIndex)
        } }
    }

    private fun contentHunks(oldContent: String, newContent: String, id: String): List<DiffHunk> {
        val before = lines(oldContent)
        val after = lines(newContent)
        var prefix = 0
        while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(before.size, after.size) - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        if (prefix == before.size && prefix == after.size) return emptyList()

        val oldMiddle = before.subList(prefix, before.size - suffix)
        val newMiddle = after.subList(prefix, after.size - suffix)
        val middle = if (oldMiddle.size + newMiddle.size > MaxMiddleLines) {
            oldMiddle.map { Edit(Kind.Delete, it) } + newMiddle.map { Edit(Kind.Add, it) }
        } else shortestEdits(oldMiddle, newMiddle)
        val edits = before.take(prefix).map { Edit(Kind.Equal, it) } + middle +
            before.takeLast(suffix).map { Edit(Kind.Equal, it) }
        val oldAt = IntArray(edits.size + 1)
        val newAt = IntArray(edits.size + 1)
        edits.forEachIndexed { index, edit ->
            oldAt[index + 1] = oldAt[index] + if (edit.kind != Kind.Add) 1 else 0
            newAt[index + 1] = newAt[index] + if (edit.kind != Kind.Delete) 1 else 0
        }
        val ranges = mutableListOf<IntRange>()
        for (index in edits.indices) {
            if (edits[index].kind == Kind.Equal) continue
            val start = maxOf(0, index - ContextLines)
            val end = minOf(edits.size, index + ContextLines + 1)
            val last = ranges.lastOrNull()
            if (last != null && start <= last.last + 1) ranges[ranges.lastIndex] = last.first until maxOf(last.last + 1, end)
            else ranges += start until end
        }
        return ranges.mapIndexed { hunkIndex, range ->
            val start = range.first
            val end = range.last + 1
            val oldCount = oldAt[end] - oldAt[start]
            val newCount = newAt[end] - newAt[start]
            DiffHunk(
                id = "$id:hunk_$hunkIndex",
                oldStart = if (oldCount == 0 && oldAt[start] == 0) 0 else oldAt[start] + 1,
                oldCount = oldCount,
                newStart = if (newCount == 0 && newAt[start] == 0) 0 else newAt[start] + 1,
                newCount = newCount,
                lines = (start until end).map { lineIndex ->
                    val edit = edits[lineIndex]
                    DiffLine(
                        id = "$id:hunk_$hunkIndex:line_${lineIndex - start}",
                        type = when (edit.kind) {
                            Kind.Equal -> DiffLineType.Context
                            Kind.Delete -> DiffLineType.Deleted
                            Kind.Add -> DiffLineType.Added
                        },
                        text = edit.text,
                        noNewline = !edit.line.ended,
                        oldLineNumber = if (edit.kind == Kind.Add) null else oldAt[lineIndex] + 1,
                        newLineNumber = if (edit.kind == Kind.Delete) null else newAt[lineIndex] + 1,
                    )
                },
            )
        }
    }

    /** Myers shortest edit script over the changed middle, with a bounded input size. */
    private fun shortestEdits(old: List<SourceLine>, new: List<SourceLine>): List<Edit> {
        val n = old.size
        val m = new.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = mutableListOf<IntArray>()
        var depth = 0
        run search@{
            for (d in 0..max) {
                for (k in -d..d step 2) {
                    val index = offset + k
                    var x = if (k == -d || (k != d && v[index - 1] < v[index + 1]))
                        v[index + 1] else v[index - 1] + 1
                    var y = x - k
                    while (x < n && y < m && old[x] == new[y]) { x++; y++ }
                    v[index] = x
                    if (x >= n && y >= m) {
                        trace += v.copyOf()
                        depth = d
                        return@search
                    }
                }
                trace += v.copyOf()
            }
        }
        var x = n
        var y = m
        val reversed = mutableListOf<Edit>()
        for (d in depth downTo 1) {
            val previous = trace[d - 1]
            val k = x - y
            val previousK = if (k == -d || (k != d && previous[offset + k - 1] < previous[offset + k + 1]))
                k + 1 else k - 1
            val previousX = previous[offset + previousK]
            val previousY = previousX - previousK
            while (x > previousX && y > previousY) {
                reversed += Edit(Kind.Equal, old[--x])
                y--
            }
            if (x == previousX) reversed += Edit(Kind.Add, new[--y])
            else reversed += Edit(Kind.Delete, old[--x])
        }
        while (x > 0 && y > 0) { reversed += Edit(Kind.Equal, old[--x]); y-- }
        while (x > 0) reversed += Edit(Kind.Delete, old[--x])
        while (y > 0) reversed += Edit(Kind.Add, new[--y])
        return reversed.asReversed()
    }
}
