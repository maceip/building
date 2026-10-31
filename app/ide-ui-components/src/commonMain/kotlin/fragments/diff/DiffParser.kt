package fragments.diff

/**
 * Standard Unified Diff Parser for Git patches and multi-file diff outputs.
 */
object DiffParser {
    private val HUNK_HEADER_REGEX = Regex("""^@@\s*-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s*@@(.*)$""")
    private val DIFF_GIT_REGEX = Regex("""^diff --git a/(.*) b/(.*)$""")

    /**
     * Parse a multi-file unified diff string into a list of [FileDiffState] instances.
     */
    fun parse(patchText: String): List<FileDiffState> {
        val lines = patchText.lines()
        val fileDiffs = mutableListOf<FileDiffState>()

        var currentFileId: String? = null
        var currentOldPath: String? = null
        var currentNewPath: String? = null
        var currentStatus = FileChangeStatus.Modified
        val currentHunks = mutableListOf<DiffHunk>()

        var currentHunkOldStart = 0
        var currentHunkOldCount = 0
        var currentHunkNewStart = 0
        var currentHunkNewCount = 0
        var currentHunkHeaderText = ""
        val currentHunkLines = mutableListOf<DiffLine>()

        var currentOldLine = 0
        var currentNewLine = 0
        var inHunk = false
        var hunkCounter = 0

        fun flushHunk() {
            if (inHunk && currentFileId != null) {
                currentHunks.add(
                    DiffHunk(
                        id = "$currentFileId:hunk_${hunkCounter++}",
                        oldStart = currentHunkOldStart,
                        oldCount = currentHunkOldCount,
                        newStart = currentHunkNewStart,
                        newCount = currentHunkNewCount,
                        headerText = currentHunkHeaderText.trim(),
                        lines = currentHunkLines.toList(),
                    )
                )
                currentHunkLines.clear()
                inHunk = false
            }
        }

        fun flushFile() {
            flushHunk()
            if (currentFileId != null && currentNewPath != null) {
                fileDiffs.add(
                    FileDiffState(
                        id = currentFileId!!,
                        oldPath = currentOldPath,
                        newPath = currentNewPath!!,
                        status = currentStatus,
                        hunks = currentHunks.toList(),
                    )
                )
                currentHunks.clear()
                currentFileId = null
                currentOldPath = null
                currentNewPath = null
                currentStatus = FileChangeStatus.Modified
                hunkCounter = 0
            }
        }

        for (line in lines) {
            val gitMatch = DIFF_GIT_REGEX.matchEntire(line)
            if (gitMatch != null) {
                flushFile()
                currentOldPath = gitMatch.groupValues[1]
                currentNewPath = gitMatch.groupValues[2]
                currentFileId = currentNewPath
                continue
            }

            if (line.startsWith("--- ")) {
                val path = line.removePrefix("--- ").removePrefix("a/").trim()
                if (path != "/dev/null") {
                    currentOldPath = path
                } else {
                    currentStatus = FileChangeStatus.Added
                }
                continue
            }

            if (line.startsWith("+++ ")) {
                val path = line.removePrefix("+++ ").removePrefix("b/").trim()
                if (path != "/dev/null") {
                    currentNewPath = path
                    if (currentFileId == null) currentFileId = path
                } else {
                    currentStatus = FileChangeStatus.Deleted
                }
                continue
            }

            val hunkMatch = HUNK_HEADER_REGEX.matchEntire(line)
            if (hunkMatch != null) {
                flushHunk()
                inHunk = true
                currentHunkOldStart = hunkMatch.groupValues[1].toInt()
                currentHunkOldCount = hunkMatch.groupValues[2].ifEmpty { "1" }.toInt()
                currentHunkNewStart = hunkMatch.groupValues[3].toInt()
                currentHunkNewCount = hunkMatch.groupValues[4].ifEmpty { "1" }.toInt()
                currentHunkHeaderText = hunkMatch.groupValues[5]

                currentOldLine = currentHunkOldStart
                currentNewLine = currentHunkNewStart

                if (currentFileId == null) {
                    currentFileId = "diff_patch"
                    currentNewPath = "diff_patch"
                }
                continue
            }

            if (inHunk) {
                if (line.startsWith("+")) {
                    currentHunkLines.add(
                        DiffLine(
                            type = DiffLineType.Added,
                            text = line.substring(1),
                            oldLineNumber = null,
                            newLineNumber = currentNewLine++,
                        )
                    )
                } else if (line.startsWith("-")) {
                    currentHunkLines.add(
                        DiffLine(
                            type = DiffLineType.Deleted,
                            text = line.substring(1),
                            oldLineNumber = currentOldLine++,
                            newLineNumber = null,
                        )
                    )
                } else if (line.startsWith(" ")) {
                    val text = line.substring(1)
                    currentHunkLines.add(
                        DiffLine(
                            type = DiffLineType.Context,
                            text = text,
                            oldLineNumber = currentOldLine++,
                            newLineNumber = currentNewLine++,
                        )
                    )
                } else if (line.startsWith("\\ No newline at end of file")) {
                    if (currentHunkLines.isNotEmpty()) {
                        val last = currentHunkLines.lastIndex
                        currentHunkLines[last] = currentHunkLines[last].copy(noNewline = true)
                    }
                }
            }
        }

        flushFile()
        return fileDiffs
    }
}
