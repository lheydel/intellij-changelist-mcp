# Changelist MCP

A JetBrains IDE plugin that exposes IntelliJ's **local changelists** to the IDE's built-in MCP
server, so an MCP client (e.g. Claude Code) can read changelist state, draft commit messages from a
changelist's diff, and organize modified files into changelists for you to review and commit.

> **Read + organize only.** This plugin never commits, pushes, stashes, reverts, shelves, or edits file
> contents. It reads changelist state and rearranges which changelist a change belongs to. To organize
> *new* files it schedules them for addition (add-to-VCS) into a changelist — the git equivalent of
> marking a new file to be tracked — but you still review and commit manually. That boundary is absolute.

## Why

A common flow is: the agent implements → you review the diffs in IntelliJ (per changelist, with
granular per-line selection) → you commit in IntelliJ → you push. Two gaps make that awkward:

- `git diff` / `git diff --cached` doesn't reflect IntelliJ's changelist grouping (IntelliJ does partial
  commits without staging via the index), so the agent can't see "the files I'm about to commit as one unit."
- You don't want to hand-sort implementation changes into changelists, and you don't want to copy/paste
  commit messages out of a chat.

This plugin closes both gaps while keeping commit & push firmly in your hands.

## Requirements

- **IntelliJ IDEA 2026.1 or newer** (build `261+`). The MCP server is bundled in the IDE since 2025.2, and
  this plugin uses the 2026.1 MCP API (`McpToolset` + the `McpCallInfoKt` project accessor). It will not
  load on older builds — `sinceBuild` is `261`.
- Works on both IDEA Community and Ultimate (since 2025.3 they're a single distribution).

## Tools

| Tool | Arguments | Purpose |
|---|---|---|
| `list_changelists` | — | Read every local changelist with its files (project-relative paths; `changeType` ∈ MODIFIED/ADDED/DELETED/MOVED), plus `isDefault`/`isReadOnly`, and the project's `unversionedFiles` (new, not-yet-tracked files). |
| `refresh_changelists` | — | Re-scan the working tree and recompute changelist state. **Call this after editing files outside the IDE** (e.g. via Claude's own tools), before `list_changelists` — IntelliJ doesn't see external edits until it refreshes. |
| `get_changelist_diff` | `name` | Unified diff for one changelist (capped at 200 KB), for drafting a commit message. |
| `create_changelist` | `name`, `comment?`, `files?`, `setActive?` | Create a changelist and put files into it: modified files are moved; **unversioned (new) files are scheduled for addition** and reported in `addedFiles`. Non-matching / missing files go to `skippedFiles`; the call still succeeds. |
| `move_to_changelist` | `name`, `files` | Put files into an **existing** changelist (modified → moved, new → scheduled for addition / `addedFiles`). Fails if the changelist is missing (never auto-creates) or read-only. |
| `move_lines_to_changelist` | `name`, `file`, `lineRanges` | Move only specific **hunks** of one file into an existing changelist, leaving its other changes in place. See [Parallel sessions](#parallel-sessions). |
| `set_changelist_comment` | `name`, `comment` | Set a changelist's comment, which IntelliJ uses as the **default commit message** — so the commit dialog is pre-filled. **Use a *named* changelist:** the default `Changes` ignores its comment (the dialog shows the last commit message instead). |
| `delete_changelist` | `name`, `moveContentsTo?` | Delete a changelist, relocating its files first (default target `"Default"`). Refuses to delete `Default`. |

All five register through a single `McpToolset`; they appear alongside the IDE's built-in MCP tools.

## Intended workflow

1. The agent finishes implementing.
2. It calls `refresh_changelists` (its edits were written to disk outside the IDE), then `list_changelists`,
   and **leaves user-curated buckets alone** (anything read-only, or named like "do not commit", "local",
   "wip-keep").
3. It groups the new changes into logical **named** changelists with `create_changelist` /
   `move_to_changelist` — one per intended commit. (Not the default `Changes`: the commit dialog ignores
   the default changelist's description, so a comment set there won't pre-fill — see below.)
4. It drafts a message from `get_changelist_diff` and writes it with `set_changelist_comment`, so your
   commit dialog opens pre-filled — no copy/paste.
5. **You** review in IntelliJ, reshuffle freely, and commit per group. **You** push. The plugin never does.

### Parallel sessions

When several agent sessions share one working tree, a single file can hold changes from more than one
session. The whole-file tools (`create_changelist` / `move_to_changelist`) must not be used on such a file —
they'd drag another session's work along. Instead:

- Read `get_changelist_diff` to find the line numbers of *your* hunks (the `+` side of each `@@` header).
- Call `move_lines_to_changelist(name, file, lineRanges)` with those 1-based line ranges. Only the hunks
  overlapping your ranges move; the rest stay put.

This relies on IntelliJ's partial-changelist (per-hunk) tracking. It's best-effort: tracker initialization is
asynchronous and hunk boundaries come from a slightly different diff engine than the unified diff, so rare
edge cases may move a neighbouring hunk. The result reports exactly which hunks moved and which requested
ranges matched nothing.

## Build

```bash
./gradlew buildPlugin
```

Produces the installable archive at `build/distributions/intellij-changelist-mcp-<version>.zip`.

Other useful tasks:

```bash
./gradlew verifyPlugin   # plugin-structure + binary-compatibility check against the target IDE
./gradlew runIde         # launch a sandbox IDE with the plugin loaded (isolated from your real IDE)
```

## Install

In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the built `.zip`, and restart.

## Connect an MCP client

The plugin's tools are served by the IDE's built-in MCP server, so connecting is the same as for any
JetBrains MCP tool:

1. **Settings → Tools → MCP Server** → enable the server.
2. **Auto-configure Claude Code** from that page (the IDE detects the client and writes the correct SSE
   entry itself), or add it manually with the URL shown there:
   ```bash
   claude mcp add --transport sse intellij http://localhost:<port>/sse
   ```
3. Verify with `claude mcp list`, then `/mcp` in a session to see the changelist tools alongside the built-ins.

> When several projects/instances are open, MCP calls resolve the target by **project path** — pass the
> working directory as `projectPath` (the framework injects this argument into every tool).

## Limitations

- **Single VCS root.** Path resolution uses `project.basePath`; multi-root projects are out of scope.
- **No streaming.** Large diffs are truncated at 200 KB with a marker.
- **Partial moves are best-effort** — see [Parallel sessions](#parallel-sessions).

## Development notes

The build targets a specific toolchain (each pinned for a concrete reason):

- IntelliJ Platform Gradle Plugin **2.16.0** → requires **Gradle 9+** (wrapper is 9.4.1).
- **Kotlin 2.3.21** — the 2026.1 platform ships kotlin-stdlib metadata 2.3.0; an older compiler can't read it.
- **JVM 21**.
- Platform dependency is `intellijIdea("2026.1.3")` (the `IC`/`IU` artifacts merged since 2025.3).
- Depends on the bundled `com.intellij.mcpServer` plugin and the bundled modules
  `intellij.platform.vcs.impl` (for `ChangeListManagerEx`, the patch builder, the line-status tracker) and
  `intellij.platform.vcs.impl.shared` (for `LocalRange`).
- `kotlinx-serialization-json` is `compileOnly` — the platform ships its own copy; bundling ours would clash.

Source layout:

```
src/main/kotlin/dev/kosmio/changelistmcp/
  ChangelistToolset.kt   # the McpToolset with all tool methods
  Models.kt              # @Serializable result/DTO types
src/main/resources/META-INF/plugin.xml
```
