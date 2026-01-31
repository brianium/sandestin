# Deep Grep - Implementation Plan

## Overview

Extend `grep` to recursively search all metadata in effect registrations, enabling conceptual discovery across Malli schema parameter descriptions and any library-provided metadata keys.

## Prerequisites

- [x] Review current `describe.clj` implementation
- [x] Understand Malli schema property structure

## Phase 1: Schema Description Extraction

Create a helper to walk Malli schemas and extract all `:description` values.

- [x] Add `walk-schema-descriptions` function
  - Input: Malli schema (vector/map form)
  - Output: Concatenated string of all `:description` values found
  - Handle: `:map` properties, `:tuple` elements, `:or`/`:and` branches, nested schemas
- [x] Add unit tests for schema walking
  - Simple `:map` with property descriptions
  - Nested maps
  - Union types (`:or`)
  - Tuple schemas

## Phase 2: Registration Text Extraction

Create a helper to extract all searchable text from a registration description map.

- [x] Add `registration->searchable-text` function
  - Extract `::s/key` (stringify)
  - Extract `::s/description`
  - Walk `::s/schema` for Malli `:description` properties
  - Recursively stringify all non-core keys (library metadata like `::phandaal/returns`, `::foo/examples`, etc.)
  - Skip `::s/handler` (functions aren't searchable text)
- [x] Add unit tests for text extraction
  - Minimal registration (just key + description)
  - Registration with schema containing param descriptions
  - Registration with arbitrary library-provided keys (verify they're searched)

## Phase 3: Update grep Function

Modify `grep` to use the new extraction helper.

- [x] Replace current filtering logic:
  ```clojure
  ;; Before
  (or (re-find pattern-str (str (::s/key item)))
      (re-find pattern-str (str (::s/description item))))

  ;; After
  (re-find pattern-str (registration->searchable-text item))
  ```
- [x] Add integration tests
  - Search finds effect by param description in Malli schema
  - Search finds effect by library-provided metadata (e.g., `::phandaal/returns`)
  - Search finds effect by nested content in examples
  - Existing tests still pass (backward compat)

## Phase 4: Documentation

- [x] Update `grep` docstring to mention deep search capability
- [x] Add example in docstring showing param description match
- [ ] Update any relevant documentation

## Rollout Plan

1. Implement in feature branch
2. Run full test suite
3. Test against Phandaal registry manually
4. Merge to main

## Rollback Plan

If issues arise:
1. Revert to previous `grep` implementation
2. The change is isolated to `describe.clj`
