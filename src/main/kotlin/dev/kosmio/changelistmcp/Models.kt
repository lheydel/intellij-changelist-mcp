package dev.kosmio.changelistmcp

import kotlinx.serialization.Serializable

/** One file inside a changelist. [changeType] ∈ MODIFIED / ADDED / DELETED / MOVED. */
@Serializable
data class FileEntry(val path: String, val changeType: String)

/** A single local changelist with its files and metadata. */
@Serializable
data class ChangelistInfo(
    val name: String,
    val comment: String,
    val isDefault: Boolean,
    val isReadOnly: Boolean,
    val files: List<FileEntry>,
)

/**
 * Root result for `list_changelists` (wrapper object so the JSON schema has a named root).
 * [unversionedFiles] are project-relative paths of files not yet under version control (new files);
 * they belong to no changelist until scheduled for addition.
 */
@Serializable
data class ChangelistsResult(
    val changelists: List<ChangelistInfo>,
    val unversionedFiles: List<String>,
)

/** Result for `get_changelist_diff`. */
@Serializable
data class DiffResult(val name: String, val unifiedDiff: String)

/** A file that could not be moved, with a machine-readable [reason]. */
@Serializable
data class SkippedFile(val path: String, val reason: String) // not_modified | not_found | source_readonly

/**
 * Result for `move_to_changelist`. [addedFiles] are previously-unversioned files that were scheduled
 * for addition (add-to-VCS) into the target changelist.
 */
@Serializable
data class MoveResult(
    val movedFiles: List<String>,
    val addedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
)

/**
 * Result for `create_changelist`. [addedFiles] are previously-unversioned files that were scheduled
 * for addition (add-to-VCS) into the new changelist.
 */
@Serializable
data class CreateResult(
    val name: String,
    val movedFiles: List<String>,
    val addedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
)

/** Result for `delete_changelist`. */
@Serializable
data class DeleteResult(val deleted: Boolean)

/** Result for `set_changelist_comment`. */
@Serializable
data class CommentResult(val name: String, val comment: String)

/** A 1-based, inclusive current-file line range, as read from the `+` side of a diff hunk header. */
@Serializable
data class LineRange(val start: Int, val end: Int)

/** One hunk that was moved by `move_lines_to_changelist`. */
@Serializable
data class MovedHunk(val lines: String, val fromChangelistId: String?)

/** Result for `move_lines_to_changelist`. */
@Serializable
data class MoveLinesResult(
    val file: String,
    val targetChangelist: String,
    val movedHunks: List<MovedHunk>,
    val unmatchedRanges: List<String>, // requested ranges that matched no hunk
)
