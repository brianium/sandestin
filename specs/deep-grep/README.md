---
title: "Deep Grep"
status: planned
date: 2026-01-30
priority: 30
---

# Deep Grep

## Overview

Extend the `grep` function to recursively search all discoverable metadata, not just the top-level `::s/description` and effect key. This enables conceptual search across parameter descriptions, return schemas, examples, and custom user metadata.

## Goals

- Enable `grep` to find effects based on any metadata content
- Search Malli schema `:description` properties
- Search `:returns` schemas and their descriptions
- Search `:examples` content
- Search custom user-defined keys (`:see-also`, etc.)
- Maintain backward compatibility with existing grep behavior

## Non-Goals

- Full-text search with ranking/relevance scoring
- Fuzzy matching or typo tolerance
- Indexing or caching of searchable content

## Key Decisions

Summarize important decisions made during research. See [research.md](research.md) for details.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Text extraction | Recursive walk | Simple, handles nested structures |
| Schema descriptions | Walk Malli properties | Standard Malli metadata location |
| Performance | No caching | Registry is static per dispatch |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [ ] Phase 1: Extract searchable text helper
- [ ] Phase 2: Update grep to use deep extraction
- [ ] Phase 3: Testing & documentation
