# CLAUDE.md — working on this plugin

A JetBrains plugin that contributes changelist tools to IntelliJ's **bundled** MCP server. See
`README.md` for what it does, the tool surface, and install/connect steps — this file is for editing the
code. Most of what's below is empirical: it was learned by hitting it, and is **not** visible from
reading the source.

## Safety boundary (absolute)

Read + organize only. **Never** add commit / push / stash / revert / shelve, or any tool that edits file
contents — not even a private helper that could be wired up later. The *one* mutation the plugin makes is
scheduling new (unversioned) files for addition (add-to-VCS) so they can be grouped. The user reviews and
commits in IntelliJ.

## Build & test

- Java 21; Gradle wrapper is 9.4.1 (`./gradlew`).
- `./gradlew buildPlugin verifyPlugin --no-build-cache` — keep `--no-build-cache`: when the user's IDE is
  building the same `build/` dir, the build cache fails to pack (`Could not get file mode …`). Not a code error.
- **Always run `verifyPlugin`** — it binary-checks the plugin against the target IDE build(s) and catches API
  drift that compilation alone misses.
- Artifact: `build/distributions/intellij-changelist-mcp-<version>.zip`.
- **Every code change needs a reinstall to test live**: Settings → Plugins → ⚙ → Install Plugin from Disk →
  the zip → **restart**. There is no hot reload. `./gradlew runIde` gives an isolated sandbox IDE instead.

## Toolchain — pinned for hard reasons (don't "upgrade" blindly)

- `intellijIdea("2026.1.3")`, **not** `create("IC", …)` — IC/IU stopped being separate Maven artifacts at 2025.3.
- IntelliJ Platform Gradle Plugin **2.16+** requires **Gradle 9+** (hence the 9.4.1 wrapper).
- Kotlin **2.3.21** — the 2026.1 platform ships kotlin-stdlib metadata 2.3.0; an older compiler can't read it
  and you get a cascade of bogus "unresolved reference" errors on platform classes.
- `sinceBuild = "261"` (2026.1+): the `project` coroutine accessor resolves to `McpCallInfoKt`, which doesn't
  exist pre-2026.1.
- Dependencies that aren't obvious: `bundledPlugin("com.intellij.mcpServer")`;
  `bundledModule("intellij.platform.vcs.impl")` (for `ChangeListManagerEx`, the patch builder, the
  line-status tracker, `ChangeListManagerImpl.editComment`); `bundledModule("intellij.platform.vcs.impl.shared")`
  (for `LocalRange`). `kotlinx-serialization-json` is `compileOnly` — the platform ships its own; bundling clashes.
- The plugin id must **not** contain the word "intellij" (the verifier rejects it).

## The MCP tool API (2026.1)

- One class implements the marker `com.intellij.mcpserver.McpToolset`; each tool is a `suspend fun` annotated
  `@McpTool` + `@McpDescription`. Annotate **every parameter** too — those descriptions become the JSON schema.
- Tool name = method name. Return an `@Serializable` data class (in `Models.kt`). Errors: `mcpFail("…")` (returns Nothing).
- Project: `currentCoroutineContext().project` (import `com.intellij.mcpserver.project`).
- Register all tools via the single `<mcpToolset>` in `plugin.xml`.
- The framework auto-injects a `projectPath` parameter into every tool — that's the multi-project/instance
  routing mechanism; don't declare it yourself.

## Threading — the most-bitten area

- Reads (`list_changelists`, `get_changelist_diff`): wrap in `readAction { }`.
- Whole-changelist mutations (move / create / delete): `edtWriteAction { }`. Re-fetch `Change` objects **inside**
  the action; never cache one across a suspension.
- **Scheduling unversioned files for addition** must run OFF the write lock AND off the EDT. Use
  `ScheduleForAdditionAction.Manager.addUnversionedFilesToVcsInSync(project, list, files, Consumer{})` inside
  `withContext(Dispatchers.IO)`. The public `ChangeListManagerImpl.addUnversionedFiles` uses a **modal** task
  that silently **no-ops** under a write lock — do not use it.
- **Partial (hunk) moves**: get the file's `PartialLocalLineStatusTracker` from `LineStatusTrackerManager`
  (request it + poll until `getRanges()` is populated — tracker init is async), then call
  `moveToChangelist(Range, list)` per matched hunk. Do **not** use `moveToChangelist(BitSet, …)` — that BitSet is
  **line numbers**, not range indices, so it silently moves nothing.
- **Refresh** (`refresh_changelists`): `VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)` +
  `VcsDirtyScopeManager.getInstance(project).markEverythingDirty()` + `ChangeListManagerEx.waitForUpdate()`, all on
  `Dispatchers.IO`. It returns a bare confirmation, not counts: the unversioned-files list settles a beat after
  `waitForUpdate()`, so any count read in the same call is racy — callers read `list_changelists` afterward.

## Behavioral gotchas (verified live, not inferable from code)

- **Resolve requested paths against the change set, not the VFS.** Deleted and moved files have no live
  `VirtualFile`, so `findFileByPath` returns null and a naive resolver wrongly skips them. Index
  `ChangeListManager`'s changes by project-relative path; only fall back to a VFS + `isUnversioned` check for new files.
- **`set_changelist_comment` pre-fills the commit dialog only for a NAMED changelist.** The default ("Changes")
  ignores its description — the dialog shows the last commit message instead. Always group into a named changelist.
- **Agent file edits bypass IntelliJ's VFS.** Anything written to disk outside the IDE is invisible until a
  re-scan, so the workflow is `refresh_changelists` → then read/move.
- `editComment` is on the concrete `ChangeListManagerImpl` (`getInstanceImpl`), not the `ChangeListManager`/`…Ex`
  interfaces.

## Source layout

- `src/main/kotlin/dev/kosmio/changelistmcp/ChangelistToolset.kt` — the `McpToolset`, all tools, private helpers.
- `src/main/kotlin/dev/kosmio/changelistmcp/Models.kt` — `@Serializable` result DTOs.
- `src/main/resources/META-INF/plugin.xml` — one `<mcpToolset>` registration.

## Inspecting platform APIs

When unsure of a signature, decompile against the resolved SDK rather than guessing:
`javap -p -cp "<idea>/lib/*.jar" <fqcn>`, where `<idea>` is under
`~/.gradle/caches/<gradle>/transforms/**/transformed/idea-<version>/`. For readable Java, the IDE's own
`mcp__idea__read_file` reads `…/lib/foo.jar!/pkg/Bar.class`.
