# ADR-004 Accept that Gradle is our life now

**Status:** Accepted (reluctantly) · **Date:** 2025-09-03

## Context

Since ADR-001 the iOS developers also run Gradle. Morale has been observed to drop by 30% during a sync.

## Decision

- Enable the configuration cache and the build cache.
- Use a version catalog (`libs.versions.toml`) so nobody edits version numbers in eleven places.
- Buy a faster CI runner. Name it *Enterprise*.
- Never say "it's just a small Gradle change" in standup again.

## Consequences

Builds are 40% faster. Nobody believes it.
