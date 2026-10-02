# Rename "Root" → "Home"

## Context

The default top-level note is currently named "Root" — it lives on disk as `Root.md` and the UI breadcrumb falls back to the label `"Root"` whenever a pane has no active file. "Home" reads better as a user-facing name, so we want to swap the on-disk filename and every visible string in one pass. Per project policy ([feedback_no_theme_format_compat]), no backwards-compat is needed — existing vaults keep their old `Root.md` and won't be auto-migrated; this change only affects fresh installs and the displayed label.

## Changes

### 1. On-disk filename — `Root.md` → `Home.md`

**File:** `client/src/commonMain/kotlin/se/soderbjorn/lunarbor/data/NoteRepository.kt:853`

```kotlin
const val DEFAULT_FILE_NAME: String = "Home$NOTE_EXTENSION"
```

Everything else flows from this constant: `NoteRepository.rootFileName` defaults to it (line 91), `DocumentRegistry.acquire(rootFileName)` and `loadRoot()` use it (line 152), and `PaneBackingViewModel` is constructed with it. No other production code hard-codes `"Root.md"`.

### 2. UI fallback label — `"Root"` → `"Home"`

**File:** `web/src/jsMain/kotlin/se/soderbjorn/lunarbor/main/AppShell.kt:1127-1131`

```kotlin
private fun activeFileDisplayName(paneId: String): String {
    val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState ?: return "Home"
    val fileRel = backing.activeFileRel
    if (fileRel.isEmpty()) return "Home"
    return fileRel.substringAfterLast('/').removeSuffix(".md").ifBlank { "Home" }
}
```

Also update the KDoc on this function (line 1125 references `"Root"`) and the three nearby comments that mention the old name:
- `AppShell.kt:988` — "leading 'Root' segment that clears the zoom" → "leading 'Home' segment …"
- `AppShell.kt:1012` — "matching old 'Root' behaviour" → "matching old 'Home' behaviour"
- `AppShell.kt:1710` — "zoom path / 'Root' label" → "zoom path / 'Home' label"
- `MainScreen.kt:103` — "Falls back to 'Root' when…" → "Falls back to 'Home' when…"

### 3. Tests — update hardcoded `"Root.md"` references

**Files:**
- `client/src/commonTest/kotlin/se/soderbjorn/lunarbor/data/VaultIndexTest.kt` (~40 occurrences)
- `client/src/commonTest/kotlin/se/soderbjorn/lunarbor/data/InlineMarkdownTokenizerTest.kt` (3 occurrences)

These are test fixtures, not assertions about the literal string "Root" — a find/replace of `"Root.md"` → `"Home.md"` and the corresponding `"Root"` titles where they appear as the doubled-name pair (`Root/Root.md` → `Home/Home.md`) is sufficient. Run the `:client` test target after to confirm.

## Out of scope

- **Migration of existing vaults.** Per [feedback_no_theme_format_compat], we discard rather than migrate. Existing users keep their `Root.md` because `NoteRepository.rootFileName` is still read from `DEFAULT_FILE_NAME` only at fresh-vault init — wait, actually verify this: an existing install will, on next launch, try to load `Home.md` (which doesn't exist) and silently create an empty Home note while their populated `Root.md` becomes orphaned. **Flag for the user before executing** — this may not be acceptable even given the no-compat rule, since the orphaning is silent rather than a clean reset.

## Verification

1. **Fresh vault.** Delete (or point to a new) `lunarbor-db` directory, launch the web app, confirm:
   - Breadcrumb shows "Home"
   - A `Home.md` file is created on first edit (not `Root.md`)
2. **Tests.** From `develop/`:
   ```
   ./gradlew :client:allTests
   ```
   Expect green.
3. **Grep sweep.** After edits:
   ```
   grep -rn '"Root"' client web --include='*.kt'
   grep -rn 'Root\.md' client web --include='*.kt'
   ```
   Expect no matches outside of intentionally-kept history comments (there should be none).

## Critical files

- `client/src/commonMain/kotlin/se/soderbjorn/lunarbor/data/NoteRepository.kt` — the one constant
- `web/src/jsMain/kotlin/se/soderbjorn/lunarbor/main/AppShell.kt` — UI fallback + comments
- `web/src/jsMain/kotlin/se/soderbjorn/lunarbor/main/MainScreen.kt` — one comment
- `client/src/commonTest/kotlin/se/soderbjorn/lunarbor/data/VaultIndexTest.kt` — test fixtures
- `client/src/commonTest/kotlin/se/soderbjorn/lunarbor/data/InlineMarkdownTokenizerTest.kt` — test fixtures
