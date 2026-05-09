# Promotion policy — scalability analysis

Notes on how `PromotionPolicy` behaves when a single Notegrow tree is used as
the user's entire knowledge base, and what to do about it.

## The current rules

From `client/src/commonMain/kotlin/se/soderbjorn/notegrow/data/PromotionPolicy.kt`:

**Promote** (`shouldPromote`) requires *all* of:

- Not already promoted.
- `descendantCount >= promoteMinDescendants` — 40 in production, 3 in debug
  (`DEBUG = true` is currently on).
- `globalDepth <= MAX_DEPTH_TO_PROMOTE` (= 4) — subtrees nested deeper than
  that in the composed outline are never spun out.
- `titleLength >= 1` — empty titles would map to `untitled` filenames.

**Demote** (`shouldDemote`) is purely `descendantCount <= demoteMaxDescendants`
— 15 in production, 1 in debug. The gap to the promote threshold is
deliberate hysteresis so subtrees near the line don't flap on every autosave
tick.

Adopted foreign files carry `PromotedRef.noAutoPromote = true` and bypass the
demote check entirely; the policy itself stays a pure predicate.

## Why a hard depth cap, rather than a size-scaled one?

Plausible reasons the policy was written with `globalDepth <= 4` as a hard
gate (rather than e.g. "any depth if the subtree is large enough"):

1. **The corpus said it didn't matter.** The KDoc cites a concrete stat:
   beyond depth 4, p99 subtree size is ≤ 34 — below the 40-descendant
   promote threshold anyway. If the calibration data showed essentially zero
   deep-and-large subtrees in real notes, a hard cap is simpler and behaves
   identically on the observed distribution. A scaled cap only pays off on
   inputs that didn't exist in the sample.
2. **Simplicity at the persistence boundary.** `shouldPromote` is consulted
   on every autosave tick for every subtree. A single integer comparison is
   trivially predictable and trivially testable; a size-scaled cap adds a
   second tunable and a second mode of behavior to reason about. For a
   heuristic that's already hysteresis-banded, the author may have judged
   the extra knob not worth it.
3. **Filename / path-length pragmatics.** Each promotion adds a
   `<Name>/<Name>.md` segment to the path. A depth-7 promoted chain
   produces a 14-segment path, which gets ugly fast on disk and in the UI.
   A hard depth cap is a blunt way to keep on-disk paths shallow regardless
   of subtree size — a size-scaled rule gives that up.
4. **It's a v1 heuristic.** `DEBUG = true` is still on, suggesting the policy
   hasn't been hardened for production yet. The cap may simply be the
   conservative first cut.

Read: (1) and (3) are the substantive reasons; (2) and (4) are why it stayed
that way.

## Does this scale to "all my knowledge in one tree"?

**Short answer:** in the limit, no — and the failure mode is concrete.

### The mechanism

Promotion only fires for subtrees at `globalDepth <= 4`. Once the root file's
top-level bullets get promoted, *their* top-level bullets become depth 1 in
the composed outline, and so on. So the cap allows files to be created down
to a chain of ~5 promotions deep. After that, no matter how large a
sub-subtree grows, it stays inlined in whichever file is currently hosting
it.

### What that means in practice

- **Early on: fine.** Top-level areas (Work, Recipes, Projects, …) promote,
  then their major sections promote, and so on for ~5 levels. That's a lot
  of headroom — easily thousands of bullets distributed across many files.
- **Long-term:** the leaf-most promoted files become append-only growth
  points. Anything added *inside* a depth-5 bullet keeps accumulating in
  that file with no further splitting. A single
  `Projects/2027/Q3/BigProject/Notes.md` file can grow without bound, even
  past tens of thousands of bullets, because everything under it is at
  globalDepth ≥ 5.
- **Cost shape:** autosave rewrites the whole file on each tick, so the
  practical pain shows up as autosave latency on the largest leaf file, not
  as a hard failure.

### Will it bite a given user?

Depends on tree shape:

- **Fan-out trees** (many top-level areas, each with many sections) — depth
  4 buys a lot of headroom. A single area would need to accumulate 40+
  descendants past depth 5 before the cap matters.
- **Narrow-and-deep trees** (one root, one big project, drilling down) —
  the cap bites much sooner.

## Mitigations

Cheapest to most invasive:

1. **Raise `MAX_DEPTH_TO_PROMOTE`** (e.g. to 8 or 10). Cheap; defers the
   problem by orders of magnitude. Costs deeper on-disk paths.
2. **Replace the hard cap with a size-scaled rule.** Keep the depth cap for
   routine subtrees, but let a sufficiently large subtree promote regardless
   of depth — e.g. allow promotion at any depth if
   `descendantCount >= 2 * promoteMinDescendants`. Solves the scalability
   hole asymptotically while preserving anti-tower behavior for narrow
   chains.
3. **Drop the cap entirely** and rely on `promoteMinDescendants` +
   hysteresis alone. Simplest model; accepts the deep-thin-chain pathology
   (a long chain of barely-qualifying subtrees each spawning a file) as a
   price.

## Recommendation

Adopt **option 2** — size-scaled cap.

It preserves the original intent (don't shatter deep narrow chains into a
tower of files) while guaranteeing that any genuinely large subtree gets its
own file no matter where it lives in the outline. That's the right behavior
for a tool aiming to host an entire knowledge base in a single tree.

Concretely, replace the current gate:

```kotlin
span.globalDepth <= MAX_DEPTH_TO_PROMOTE
```

with something like:

```kotlin
(span.globalDepth <= MAX_DEPTH_TO_PROMOTE ||
    span.descendantCount >= LARGE_SUBTREE_DESCENDANTS)
```

where `LARGE_SUBTREE_DESCENDANTS` is a multiple (e.g. 2×) of
`promoteMinDescendants`. Keep the existing demote rule unchanged — the
hysteresis band still applies on the way back down.

Defer until either (a) `DEBUG` is being flipped off for the production
calibration pass, or (b) a real tree is observed bumping into the cap. The
fix is small enough that it doesn't need to land speculatively, but the
analysis above is worth keeping so the next person to touch this file
inherits the reasoning.

## How to test option 2

Testing splits into three layers, cheapest to most realistic.

### 1. Unit-test `PromotionPolicy.shouldPromote` directly

This is where the change actually lives — a pure predicate over
`SubtreeSpan`. The whole point of keeping `PromotionPolicy` annotation-free
and side-effect-free is that it's trivially testable in commonTest with no
DI, no filesystem, no flows.

Cases worth covering:

- Shallow + small → no promote (control).
- Shallow + at threshold → promote (existing behavior preserved).
- **Deep + small** (≥ old threshold but < large threshold) → no promote
  (anti-tower behavior preserved — this is the case the depth cap was
  originally protecting).
- **Deep + large** (≥ `LARGE_SUBTREE_DESCENDANTS`) → promote (the new
  behavior; this is the bug fix).
- Deep + large but `alreadyPromoted` → no re-promote.
- Deep + large but `titleLength == 0` → no promote (title floor still wins).
- Boundary: `descendantCount == LARGE_SUBTREE_DESCENDANTS - 1` at deep
  depth → no promote; `== LARGE_SUBTREE_DESCENDANTS` → promote.

Boundary tests on the new constant are the highest-value ones — they pin
down exactly where the new rule starts firing and catch off-by-one
regressions if someone later tunes the multiplier.

### 2. Integration-test `NoteRepository`'s save tick

`PromotionPolicy` only matters because `NoteRepository` consults it when
deciding whether to spin a subtree out. Build a synthetic outline (in
memory, via `FileSystem` expect/actual — which has a JVM actual you can
drive from `commonTest` or `jvmTest`), drive a save, and assert the
resulting file layout on disk:

- Construct a depth-6 subtree with 80 descendants, run the autosave path,
  assert that a `<Name>/<Name>.md` file was emitted at the expected
  location and that the parent file no longer contains the inlined subtree.
- Same shape but only 30 descendants → assert it stays inlined.
- Promote → demote round-trip: build a depth-6 subtree, save (promotes),
  shrink it below `demoteMaxDescendants`, save again (demotes), assert the
  file was reabsorbed and removed from disk. This exercises the hysteresis
  path under the new rule.

These are slower than unit tests but catch the integration mistakes that
pure-predicate tests can't — frontmatter handling, link encoding
(`SubtreeCodec`), path resolution.

### 3. Manual / corpus exercise

The KDoc cites corpus statistics ("p99 subtree size ≤ 34 beyond depth 4").
The scalability fix is fundamentally about long-tail inputs the corpus
didn't see, so corpus-style tests help calibrate but won't catch the bug —
they'll mostly show "no change" because the corpus shape doesn't trigger
the new branch. Two useful manual exercises:

- **Synthetic stress vault.** Generate a single deep-and-wide tree (e.g. 5
  promotion levels, then a depth-6 subtree with several hundred
  descendants). Open it in the running app, watch the on-disk file layout
  settle, then watch autosave latency on a typical edit. Confirms the fix
  actually moves the bottleneck.
- **Real vault dry run.** Point a copy of an existing Obsidian vault at
  the build, compare the file layout produced by the old vs. new policy.
  Should be byte-identical for typical-shape vaults; the diff is the
  evidence that option 2 is conservative on common inputs and only fires
  on the pathological ones.

### What to measure to prove it scaled

The acceptance criterion isn't just "the test passes" — it's "the largest
leaf file's size is bounded." A useful assertion in the integration test:
after saving an N-bullet tree of any shape, no single `.md` file on disk
contains more than roughly `2 * promoteMinDescendants` bullets. With the
hard depth cap that bound doesn't hold (a depth-5 file can grow without
bound); with option 2 it does. That's the property that captures "scales
to all my knowledge in one tree" in a single check.

### Recommended order

1. Add unit tests for `shouldPromote` covering the four-quadrant matrix
   (shallow/deep × small/large) before changing the code. They'll fail on
   deep+large.
2. Make the change in `PromotionPolicy.kt`. Unit tests now pass.
3. Add the `NoteRepository` integration tests for the depth-6
   large-subtree case and the round-trip.
4. Run the synthetic stress vault manually to confirm autosave latency
   behaves.

Step 1 alone catches ~all of the regression risk in the policy itself;
steps 2–4 are about confidence that the integration around it still holds.
