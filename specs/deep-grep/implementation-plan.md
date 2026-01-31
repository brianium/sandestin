# Deep Grep - Implementation Plan

## Overview

Extend `grep` to recursively search all metadata in effect registrations, enabling conceptual discovery across parameter descriptions, return schemas, examples, and custom keys.

## Prerequisites

- [ ] Review current `describe.clj` implementation
- [ ] Understand Malli schema property structure

## Phase 1: Schema Description Extraction

Create a helper to walk Malli schemas and extract all `:description` values.

- [ ] Add `walk-schema-descriptions` function
  - Input: Malli schema (vector/map form)
  - Output: Concatenated string of all `:description` values found
  - Handle: `:map` properties, `:tuple` elements, `:or`/`:and` branches, nested schemas
- [ ] Add unit tests for schema walking
  - Simple `:map` with property descriptions
  - Nested maps
  - Union types (`:or`)
  - Tuple schemas

## Phase 2: Registration Text Extraction

Create a helper to extract all searchable text from a registration description map.

- [ ] Add `registration->searchable-text` function
  - Extract `::s/key` (stringify)
  - Extract `::s/description`
  - Walk `::s/schema` for descriptions
  - Walk `:returns` schema for descriptions
  - Stringify `:examples` vector
  - Stringify `:see-also` vector
  - Stringify any other user-defined keys (non `::s/` namespaced)
- [ ] Add unit tests for text extraction
  - Minimal registration (just key + description)
  - Full registration with all fields
  - Custom user keys

## Phase 3: Update grep Function

Modify `grep` to use the new extraction helper.

- [ ] Replace current filtering logic:
  ```clojure
  ;; Before
  (or (re-find pattern-str (str (::s/key item)))
      (re-find pattern-str (str (::s/description item))))

  ;; After
  (re-find pattern-str (registration->searchable-text item))
  ```
- [ ] Add integration tests
  - Search finds effect by param description
  - Search finds effect by return schema description
  - Search finds effect by example content
  - Search finds effect by `:see-also` reference
  - Existing tests still pass (backward compat)

## Phase 4: Documentation

- [ ] Update `grep` docstring to mention deep search capability
- [ ] Add example in docstring showing param description match
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
