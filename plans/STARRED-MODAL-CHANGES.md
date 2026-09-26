# Starred modal — toggle behavior, sizing, and a DI digression

This note covers two unrelated-looking changes that ended up in the same
patch, and one architectural smell I noticed but did **not** fix. The
smell is worth flagging because it predates this patch and now applies to
one more code path.

## What changed

### 1. Toggle button (was a duplicate-create bug)

Before: clicking "Add to starred" while the active target was already
bookmarked appended a second identical bullet to `Starred.md`. The button
had `is-active` styling to *signal* the duplicate, but nothing prevented
the write.

After: when the target is already starred, the button reads
**"Remove from starred"** and clicking it removes the matching line(s).
The `addStarBtnIsActive` flag is mirrored from
`refreshAddStarButtonState` — same matching rule the active-styling
already used — so the click handler can branch without re-deriving the
state.

DOM-construction shifted from a single `innerHTML` string to two child
spans, so the label text can be swapped without rebuilding the icon.

### 2. Modal sizing & scroll

- Panel: `max-height: 80vh` → `height: min(720px, 85vh)`. The modal now
  has a tall default size regardless of how many bookmarks exist.
- Body: `min-height: 120px` → `min-height: 0`, plus an explicit
  `overflow-y: auto` in the stylesheet (was inline only). This is the
  standard flex-overflow fix: a column flex child defaults to
  `min-height: auto` (= content size), which silently disables scroll
  when content overflows. Setting it to `0` lets the child shrink and
  the `overflow-y: auto` then engages.

### 3. New repository method

`NoteRepository.removeStarredEntry(targetPathRel, targetRow)` — reads
`Starred.md`, parses each line with `SubtreeCodec.parseAnyLinkBullet`,
drops bullets whose `(path, row)` match the target, preserves
non-link lines verbatim. Match logic mirrors what the modal already uses
to flag a target as currently starred, so "the button says active" and
"remove finds something to remove" stay consistent by construction.

## The DI / singleton smell

`StarredModal` constructs **two** `NoteRepository` instances of its own:

```kotlin
// StarredModal.kt
private val fileSystem = FileSystem()
private val noteRepository = NoteRepository(fileSystem = fileSystem)
private val starredRepo = NoteRepository(
    fileSystem = fileSystem,
    rootFileName = NoteRepository.STARRED_FILE_NAME,
)
```

Meanwhile `JsAppGraph` already has an `@SingleIn(AppScope::class)`
`NoteRepository` bound to the app's `FileSystem`:

```kotlin
@Provides @SingleIn(AppScope::class)
fun provideFileSystem(): FileSystem = FileSystem()

@Provides @SingleIn(AppScope::class)
fun provideNoteRepository(fileSystem: FileSystem): NoteRepository =
    NoteRepository(fileSystem)
```

So the modal is bypassing DI to `new` two parallel repositories, which
share neither identity nor any in-memory state with the canonical one
the rest of the app uses. The KDoc on `noteRepository` even
acknowledges this — it argues the pattern is "safe: writes go through
`FileSystem` directly with no in-memory cache that could diverge."

That argument is **today** correct, because `NoteRepository` happens to
be a thin pass-through. It will not stay correct. The moment the
repository grows any of:

- a write queue / debounce so we don't thrash the disk on every keystroke
- an in-memory cache of parsed outlines
- a flow that observers (e.g. a future sidebar) subscribe to for "the
  vault changed"
- coordination with autosave to avoid write/write races on `Starred.md`

…the parallel instances diverge or race. The bug surface is invisible
right now and silent when it lands.

The same pattern applies to `starredRepo` — but that one has a real
justification: it needs `rootFileName = STARRED_FILE_NAME` to boot the
modal's private document-VM trio straight into the bookmark file. The
*shared* repository is rooted on `Root.md`, so it can't double as the
modal's reader. If we want one shared instance and a second view rooted
on `Starred.md`, the right fix is to lift `rootFileName` out of the
constructor and onto a per-call argument (or a separate "open file"
abstraction) — not to keep cloning repositories.

## Should we restore the previous behavior?

Two separate "previous behaviors" are in play; they pull in opposite
directions.

### Toggle vs. duplicate-create — keep what we have now

The old behavior was a bug. The active styling claimed the target was
"already starred" but the click still appended a duplicate. There is no
user model under which that's correct; nobody opens a starred modal,
sees a starred indicator, clicks the button, and *wants* a duplicate
bullet. Restore: no.

If we ever want a separate "duplicate this bookmark" intent (e.g. "add
this row again with a different label"), that's a different affordance —
right-click menu, a `+ Bookmark again` button — not the same button
that lights up to say "already there".

### Per-modal repository instances — restore (i.e. wire through DI)

Here the previous behavior is the cleaner one in the long run. Today
the modal works fine, but the cleanup that was *not* done in this patch
is:

1. Inject the shared `NoteRepository` (and `FileSystem`) via the
   constructor instead of `new`-ing them in `StarredModal`. The modal is
   already constructed in `AppShell.openStarredModal`, which lives
   inside the DI scope, so threading one more parameter is cheap.
2. Decide what `starredRepo` should become. Two reasonable options:
   - **Cheapest:** add `@Provides @Named("starred") NoteRepository` to
     the graph that constructs the same shared repository with a
     different `rootFileName`. This still creates two repositories, but
     at least both are managed by DI and nobody hand-rolls a
     `FileSystem()`.
   - **Cleanest:** remove `rootFileName` from the constructor entirely.
     `NoteRepository` becomes vault-rooted, not file-rooted; "the
     starred file" is a path you pass to a method, not a repo identity.
     The modal's document-VM trio then takes a path argument instead of
     piggy-backing on a repo's default-file behavior. This is more code
     but it eliminates the only legitimate reason the modal currently
     has a second repository instance.

Either way, the trajectory is: DI owns the repository, the modal asks
for one. The current code has DI owning *a* repository and the modal
secretly building two more — that's the configuration most likely to
silently bite.

### So: short answer

- **Toggle button:** keep. The old behavior was a bug.
- **Repository ownership:** the *old* behavior wasn't actually
  different — `StarredModal` was already constructing its own
  repositories before this patch. What I'd restore is the discipline
  the rest of the app follows (DI-owned singletons), not the previous
  state of this file. The patch is a good moment to do it because we
  just added a second method (`removeStarredEntry`) that lives on the
  shadowed instance, doubling the contact surface where divergence
  could leak in.

The cleanup is a separate change — it's a refactor, not a bugfix, and
bundling it would have made this patch harder to review. But it should
land soon, before someone adds caching to `NoteRepository` and
discovers the modal's bookmarks don't show up until the page reloads.
