package fragments.diff

/** Formats only accepted review items as a unified patch against the original files. */
object AcceptedPatch {
    /** Contents after accepted hunks, for reviews supplied with both original and proposed text. */
    fun applyToContent(file: FileDiffState, acceptedIds: Set<String>,
        originalContent: String, proposedContent: String): String {
        if (file.hunks.isEmpty()) return if ("${file.id}:file" in acceptedIds) proposedContent else originalContent
        val originalLines = splitLines(originalContent)
        val result = StringBuilder()
        var cursor = 0
        file.hunks.filter { it.id in acceptedIds }.forEach { hunk ->
            val start = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
            require(start in cursor..originalLines.size) { "Overlapping or invalid accepted hunk ${hunk.id}" }
            for (index in cursor until start) result.append(originalLines[index])
            cursor = start
            hunk.lines.forEach { line ->
                val text = line.text + if (line.noNewline) "" else "\n"
                when (line.type) {
                    DiffLineType.Context -> {
                        require(cursor < originalLines.size && originalLines[cursor] == text) {
                            "Context mismatch in accepted hunk ${hunk.id}"
                        }
                        result.append(originalLines[cursor++])
                    }
                    DiffLineType.Deleted -> {
                        require(cursor < originalLines.size && originalLines[cursor] == text) {
                            "Deletion mismatch in accepted hunk ${hunk.id}"
                        }
                        cursor++
                    }
                    DiffLineType.Added -> result.append(text)
                    DiffLineType.HunkHeader -> error("Hunk headers cannot appear as diff lines")
                }
            }
        }
        for (index in cursor until originalLines.size) result.append(originalLines[index])
        return result.toString()
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        content.forEachIndexed { index, char ->
            if (char == '\n') {
                lines += content.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < content.length) lines += content.substring(start)
        return lines
    }

    fun format(files: List<FileDiffState>, acceptedIds: Set<String>): String = buildString {
        files.forEach { file ->
            val acceptedHunks = file.hunks.filter { it.id in acceptedIds }
            val wholeFileAccepted = file.hunks.isEmpty() && "${file.id}:file" in acceptedIds
            if (acceptedHunks.isEmpty() && !wholeFileAccepted) return@forEach
            if (wholeFileAccepted && file.status == FileChangeStatus.Modified && !file.isBinary) return@forEach

            val oldPath = file.oldPath ?: file.newPath
            val oldName = if (file.status == FileChangeStatus.Added) "/dev/null" else "a/$oldPath"
            val newName = if (file.status == FileChangeStatus.Deleted) "/dev/null" else "b/${file.newPath}"
            append("diff --git a/$oldPath b/${file.newPath}\n")
            when (file.status) {
                FileChangeStatus.Added -> append("new file mode 100644\n")
                FileChangeStatus.Deleted -> append("deleted file mode 100644\n")
                FileChangeStatus.Renamed -> {
                    append("rename from $oldPath\n")
                    append("rename to ${file.newPath}\n")
                }
                FileChangeStatus.Modified -> Unit
            }
            if (file.isBinary) {
                append("Binary files $oldName and $newName differ\n")
                return@forEach
            }
            append("--- $oldName\n")
            append("+++ $newName\n")
            var acceptedOffset = 0
            acceptedHunks.forEach { hunk ->
                val newStart = when {
                    hunk.oldCount == 0 -> hunk.oldStart + acceptedOffset + 1
                    hunk.newCount == 0 -> hunk.oldStart + acceptedOffset - 1
                    else -> hunk.oldStart + acceptedOffset
                }.coerceAtLeast(0)
                append("@@ -${hunk.oldStart},${hunk.oldCount} +$newStart,${hunk.newCount} @@")
                if (hunk.headerText.isNotBlank()) append(" ${hunk.headerText}")
                append('\n')
                hunk.lines.forEach { line ->
                    append(when (line.type) {
                        DiffLineType.Context -> ' '
                        DiffLineType.Added -> '+'
                        DiffLineType.Deleted -> '-'
                        DiffLineType.HunkHeader -> error("Hunk headers cannot appear as diff lines")
                    })
                    append(line.text)
                    append('\n')
                    if (line.noNewline) append("\\ No newline at end of file\n")
                }
                acceptedOffset += hunk.newCount - hunk.oldCount
            }
        }
    }
}
