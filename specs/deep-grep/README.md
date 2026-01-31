---
title: "Deep Grep"
status: completed
date: 2026-01-30
priority: 30
---

# Deep Grep

## Overview

Extend the `grep` function to recursively search all discoverable metadata, not just the top-level `::s/description` and effect key. This enables conceptual search across parameter descriptions and any library-provided metadata.

## Goals

- Enable `grep` to find effects based on any metadata content
- Search Malli schema `:description` properties (parameter docs)
- Search all non-core keys recursively (library-provided metadata)
- Maintain backward compatibility with existing grep behavior

## Non-Goals

- Full-text search with ranking/relevance scoring
- Fuzzy matching or typo tolerance
- Indexing or caching of searchable content
- Knowledge of specific library keys (grep searches everything generically)

## Key Decisions

Summarize important decisions made during research. See [research.md](research.md) for details.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Text extraction | Recursive walk of all keys | Libraries add arbitrary namespaced keys; grep doesn't need to know about them |
| Schema descriptions | Walk Malli properties | Standard Malli metadata location |
| Performance | No caching | Dev tool, registry is static per dispatch, simple is better |
| Library metadata | Search everything non-core | `::phandaal/returns`, `::foo/examples`, etc. automatically included |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [x] Phase 1: Extract searchable text helper
- [x] Phase 2: Update grep to use deep extraction
- [x] Phase 3: Testing & documentation
