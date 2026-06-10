package dev.kosmio.changelistmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerEx
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.actions.ScheduleForAdditionAction
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker
import com.intellij.openapi.vcs.ex.Range
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.StringWriter
import java.nio.file.Path
import java.util.BitSet

/**
 * Contributes changelist-management tools to IntelliJ's built-in MCP server.
 *
 * Read + organize only: this toolset never commits, pushes, stashes, reverts, shelves, or edits file
 * contents. To organize new files it may schedule them for addition (add-to-VCS) into a changelist —
 * the git equivalent of marking a new file to be tracked — but the user still reviews and commits
 * manually. That boundary is absolute.
 */
class ChangelistToolset : McpToolset {

    @McpTool
    @McpDescription("List IntelliJ local changelists with their files and metadata, plus the project's unversioned (new, not-yet-tracked) files. File paths are project-relative; changeType is one of MODIFIED/ADDED/DELETED/MOVED. New files appear under unversionedFiles until grouped into a changelist with create_changelist/move_to_changelist (which schedule them for addition).")
    suspend fun list_changelists(): ChangelistsResult {
        val project = currentCoroutineContext().project
        return readAction {
            val basePath = project.basePath ?: mcpFail("No project base path")
            val clm = ChangeListManager.getInstance(project)
            val lists = clm.changeLists.map { list ->
                ChangelistInfo(
                    name = list.name,
                    comment = list.comment ?: "",
                    isDefault = list.isDefault,
                    isReadOnly = list.isReadOnly,
                    files = list.changes.map { change ->
                        FileEntry(relativize(basePath, change), mapChangeType(change))
                    },
                )
            }
            val unversioned = clm.unversionedFilesPaths.map { relativizePath(basePath, it.path) }
            ChangelistsResult(changelists = lists, unversionedFiles = unversioned)
        }
    }

    @McpTool
    @McpDescription("Return the unified diff for a single changelist, used to draft a commit message. Output is capped at 200 KB.")
    suspend fun get_changelist_diff(
        @McpDescription("Name of the changelist to diff.") name: String,
    ): DiffResult {
        val project = currentCoroutineContext().project
        return readAction {
            val basePath = project.basePath ?: mcpFail("No project base path")
            val list = ChangeListManager.getInstance(project).changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found")
            val diff = buildUnifiedDiff(project, basePath, list.changes.toList())
            DiffResult(name = name, unifiedDiff = diff)
        }
    }

    @McpTool
    @McpDescription("Create a new changelist and optionally move the listed files into it. Currently-modified files are moved; unversioned (new) files are scheduled for addition (add-to-VCS) into the new changelist and reported in addedFiles. Files that are neither modified nor unversioned, or not found, are reported in skippedFiles; the call still succeeds.")
    suspend fun create_changelist(
        @McpDescription("Name for the new changelist.") name: String,
        @McpDescription("Optional description/comment for the changelist.") comment: String? = null,
        @McpDescription("Optional project-relative file paths to put into the new changelist (modified files are moved, new files are added).") files: List<String>? = null,
        @McpDescription("If true, make the new changelist the active (default) one.") setActive: Boolean = false,
    ): CreateResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        edtWriteAction {
            val newList = clm.addChangeList(name, comment ?: "")
            if (setActive) clm.setDefaultChangeList(newList)
        }
        val outcome = putFilesInto(project, clm, basePath, name, files ?: emptyList())
        return CreateResult(name = name, movedFiles = outcome.moved, addedFiles = outcome.added, skippedFiles = outcome.skipped)
    }

    @McpTool
    @McpDescription("Put files into an EXISTING changelist. Currently-modified files are moved; unversioned (new) files are scheduled for addition (add-to-VCS) into it and reported in addedFiles. Fails if the target changelist does not exist (it is never auto-created) or is read-only. Files that are neither modified nor unversioned, or not found, are reported in skippedFiles.")
    suspend fun move_to_changelist(
        @McpDescription("Name of the existing target changelist.") name: String,
        @McpDescription("Project-relative file paths to put into the target changelist (modified files are moved, new files are added).") files: List<String>,
    ): MoveResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        readAction {
            val target = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found — refusing to auto-create it")
            if (target.isReadOnly) mcpFail("Changelist '$name' is read-only — refusing to write into a frozen changelist")
        }
        val outcome = putFilesInto(project, clm, basePath, name, files)
        return MoveResult(movedFiles = outcome.moved, addedFiles = outcome.added, skippedFiles = outcome.skipped)
    }

    @McpTool
    @McpDescription("Delete a changelist, relocating its files first. By default the files move to the active default changelist; pass moveContentsTo to send them to a specific existing changelist instead. Refuses to delete the default changelist. Fails if the named relocation target does not exist.")
    suspend fun delete_changelist(
        @McpDescription("Name of the changelist to delete.") name: String,
        @McpDescription("Name of an existing changelist to move this list's files into before deletion. Defaults to the active default changelist (whatever it is named).") moveContentsTo: String? = null,
    ): DeleteResult {
        val project = currentCoroutineContext().project
        return edtWriteAction {
            val clm = ChangeListManagerEx.getInstanceEx(project)
            val list = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found")
            if (list.isDefault) mcpFail("Refusing to delete the Default changelist")

            val changes = list.changes.toList()
            if (changes.isNotEmpty()) {
                val target = if (moveContentsTo != null) {
                    clm.changeLists.firstOrNull { it.name == moveContentsTo }
                        ?: mcpFail("Relocation target changelist '$moveContentsTo' not found")
                } else {
                    clm.defaultChangeList
                }
                clm.moveChangesTo(target, *changes.toTypedArray())
            }
            clm.removeChangeList(list.name)
            DeleteResult(deleted = true)
        }
    }

    @McpTool
    @McpDescription(
        "Set a changelist's comment, which IntelliJ uses as the default commit message for that " +
            "changelist. Draft the message from get_changelist_diff, then set it here so the IDE's " +
            "commit dialog is pre-filled — the user reviews and commits without any copy/paste. " +
            "Setting a comment never commits or pushes.",
    )
    suspend fun set_changelist_comment(
        @McpDescription("Name of the changelist whose comment (default commit message) to set.") name: String,
        @McpDescription("The comment / default commit message to set on the changelist.") comment: String,
    ): CommentResult {
        val project = currentCoroutineContext().project
        return edtWriteAction {
            val clm = ChangeListManagerImpl.getInstanceImpl(project)
            clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found")
            clm.editComment(name, comment)
            CommentResult(name = name, comment = comment)
        }
    }

    @McpTool
    @McpDescription(
        "Move only specific line ranges (hunks) of a single file into an EXISTING changelist, leaving " +
            "the file's other changes where they are. Use this when a file contains changes from more " +
            "than one source (e.g. parallel sessions): read get_changelist_diff to find the line numbers " +
            "of YOUR hunks (the '+' side of each '@@' header), then move just those. Line numbers are " +
            "1-based and refer to the current file. Each hunk that overlaps a requested range is moved. " +
            "Fails if the target changelist is missing (never auto-created) or read-only. If the file " +
            "cannot be partially tracked, use move_to_changelist to move the whole file instead.",
    )
    suspend fun move_lines_to_changelist(
        @McpDescription("Name of the existing target changelist.") name: String,
        @McpDescription("Project-relative path of the file whose hunks to move.") file: String,
        @McpDescription("1-based, inclusive current-file line ranges to move (from the diff's '+' side).") lineRanges: List<LineRange>,
    ): MoveLinesResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$file")
            ?: mcpFail("File '$file' not found")
        if (!ensurePartialTracker(project, vFile)) {
            mcpFail(
                "No partial line-status tracker available for '$file' — it may have no changes or not be " +
                    "under partial-changelist tracking. Use move_to_changelist to move the whole file instead.",
            )
        }

        return edtWriteAction {
            val clm = ChangeListManagerEx.getInstanceEx(project)
            val target = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found — refusing to auto-create it")
            if (target.isReadOnly) mcpFail("Changelist '$name' is read-only — refusing to write into a frozen changelist")

            // Re-fetch the tracker inside the write action — never act on one cached across the suspension.
            val tracker = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(vFile)
                as? PartialLocalLineStatusTracker
                ?: mcpFail("Line-status tracker for '$file' is no longer available")

            val ranges = tracker.getRanges().orEmpty()
            val bitSet = BitSet(ranges.size)
            val movedHunks = mutableListOf<MovedHunk>()
            val matched = BooleanArray(lineRanges.size)
            ranges.forEachIndexed { i, r ->
                var hit = false
                lineRanges.forEachIndexed { j, req ->
                    if (rangeOverlaps(r, req.start, req.end)) {
                        hit = true
                        matched[j] = true
                    }
                }
                if (hit) {
                    bitSet.set(i)
                    movedHunks += MovedHunk(formatSpan(r), r.changelistId)
                }
            }
            if (!bitSet.isEmpty) tracker.moveToChangelist(bitSet, target)
            val unmatched = lineRanges.filterIndexed { j, _ -> !matched[j] }.map { "${it.start}-${it.end}" }
            MoveLinesResult(file = file, targetChangelist = name, movedHunks = movedHunks, unmatchedRanges = unmatched)
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Ensure a [PartialLocalLineStatusTracker] exists for [vFile] with its hunks computed. Trackers are
     * created lazily, so if none exists we request one (off any open editor — a loaded Document is enough)
     * and poll until its ranges are available or [MAX_TRACKER_POLLS] elapses. We intentionally do not
     * release the tracker afterwards: releasing it risks discarding the pending move before the changelist
     * manager persists it, and retaining one tracker per touched file for the session is negligible.
     */
    private suspend fun ensurePartialTracker(project: Project, vFile: VirtualFile): Boolean {
        val ltm = LineStatusTrackerManager.getInstance(project)
        if (readAction { ltm.getLineStatusTracker(vFile) } is PartialLocalLineStatusTracker) return true

        val document = readAction { FileDocumentManager.getInstance().getDocument(vFile) } ?: return false
        edtWriteAction { ltm.requestTrackerFor(document, trackerRequester) }

        repeat(MAX_TRACKER_POLLS) {
            delay(TRACKER_POLL_MS)
            val tracker = readAction { ltm.getLineStatusTracker(vFile) }
            if (tracker is PartialLocalLineStatusTracker && readAction { tracker.getRanges().orEmpty().isNotEmpty() }) {
                return true
            }
        }
        return readAction { ltm.getLineStatusTracker(vFile) } is PartialLocalLineStatusTracker
    }

    /** Whether hunk [range]'s current-file span overlaps the 1-based inclusive [reqStart]..[reqEnd]. */
    private fun rangeOverlaps(range: Range, reqStart: Int, reqEnd: Int): Boolean {
        val hasLines = range.line2 > range.line1
        // Tracker lines are 0-based half-open [line1, line2); convert to 1-based inclusive. A pure deletion
        // (line1 == line2) sits at the boundary, so match it against the line just before/after.
        val start = if (hasLines) range.line1 + 1 else maxOf(1, range.line1)
        val end = if (hasLines) range.line2 else range.line1 + 1
        return reqStart <= end && start <= reqEnd
    }

    /** Human-readable 1-based span of a hunk, e.g. "10-14" or "10(deletion)". */
    private fun formatSpan(range: Range): String =
        if (range.line2 > range.line1) "${range.line1 + 1}-${range.line2}" else "${range.line1}(deletion)"


    /** Outcome of partitioning a set of requested paths in [moveFilesInto]. */
    private class MoveOutcome(
        val moved: List<String>,
        val added: List<String>,
        val skipped: List<SkippedFile>,
    )

    /**
     * Put each project-relative path into the changelist named [targetName]: modified files are moved by
     * their [Change]; unversioned (new) files are scheduled for addition (add-to-VCS). Partitions into
     * moved / added / skipped (not_found / not_modified / source_readonly / add_failed).
     *
     * Two phases by necessity: moving changes needs the write lock (done in [edtWriteAction]), but
     * scheduling for addition runs a synchronous VCS task that must NOT hold the write lock and must be
     * off the EDT — so it runs on [Dispatchers.IO] via the non-modal `addUnversionedFilesToVcsInSync`.
     * Change objects are re-fetched inside the write action; only stable VirtualFiles cross the boundary.
     */
    private suspend fun putFilesInto(
        project: Project,
        clm: ChangeListManagerImpl,
        basePath: String,
        targetName: String,
        files: List<String>,
    ): MoveOutcome {
        val lfs = LocalFileSystem.getInstance()
        val moved = mutableListOf<String>()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<SkippedFile>()
        val toAdd = mutableListOf<VirtualFile>()

        // Phase 1: partition, and move already-tracked changes under the write lock.
        edtWriteAction {
            val target = clm.changeLists.firstOrNull { it.name == targetName }
                ?: mcpFail("Changelist '$targetName' not found")
            val toMove = mutableListOf<Change>()
            for (rel in files) {
                val vFile = lfs.findFileByPath("$basePath/$rel")
                if (vFile == null) {
                    skipped += SkippedFile(rel, "not_found")
                    continue
                }
                val change = clm.getChange(vFile)
                when {
                    change != null -> {
                        val sourceList = clm.getChangeList(change)
                        if (sourceList != null && sourceList.isReadOnly) {
                            skipped += SkippedFile(rel, "source_readonly")
                        } else {
                            toMove += change
                            moved += rel
                        }
                    }
                    clm.isUnversioned(vFile) -> {
                        toAdd += vFile
                        added += rel
                    }
                    else -> skipped += SkippedFile(rel, "not_modified")
                }
            }
            if (toMove.isNotEmpty()) clm.moveChangesTo(target, *toMove.toTypedArray())
        }

        // Phase 2: schedule unversioned files for addition — synchronous, off-EDT, no write lock, no modal.
        if (toAdd.isNotEmpty()) {
            val ok = withContext(Dispatchers.IO) {
                val target = readAction { clm.changeLists.firstOrNull { it.name == targetName } }
                if (target == null) {
                    false
                } else {
                    ScheduleForAdditionAction.Manager.addUnversionedFilesToVcsInSync(
                        project, target, toAdd, Consumer { },
                    )
                }
            }
            if (!ok) {
                skipped += added.map { SkippedFile(it, "add_failed") }
                added.clear()
            }
        }
        return MoveOutcome(moved, added, skipped)
    }

    /** Build a unified diff for [changes] via the IDE patch builder, capped at [MAX_DIFF_BYTES]. */
    private fun buildUnifiedDiff(project: Project, basePath: String, changes: List<Change>): String {
        if (changes.isEmpty()) return ""
        val patches = IdeaTextPatchBuilder.buildPatch(project, changes, Path.of(basePath), false)
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        val diff = writer.toString()
        return if (diff.length > MAX_DIFF_BYTES) {
            diff.substring(0, MAX_DIFF_BYTES) + "\n[... diff truncated at 200 KB ...]"
        } else {
            diff
        }
    }

    /** Map IntelliJ's [Change.Type] to SPEC's vocabulary. */
    private fun mapChangeType(change: Change): String = when (change.type) {
        Change.Type.MODIFICATION -> "MODIFIED"
        Change.Type.NEW -> "ADDED"
        Change.Type.DELETED -> "DELETED"
        Change.Type.MOVED -> "MOVED"
    }

    /** Strip the project base-path prefix to produce a project-relative path. */
    private fun relativize(basePath: String, change: Change): String =
        relativizePath(basePath, ChangesUtil.getFilePath(change).path)

    /** Strip the project base-path prefix from an absolute path to produce a project-relative one. */
    private fun relativizePath(basePath: String, fullPath: String): String =
        if (fullPath.startsWith(basePath)) fullPath.removePrefix(basePath).trimStart('/') else fullPath

    /** Stable token identifying this toolset as the holder of any line-status trackers it requests. */
    private val trackerRequester = Any()

    private companion object {
        const val MAX_DIFF_BYTES = 200 * 1024
        const val MAX_TRACKER_POLLS = 30
        const val TRACKER_POLL_MS = 100L
    }
}
