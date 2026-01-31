# Deep Grep - Research

## Problem Statement

The current `grep` function only searches two fields:
1. The effect/action/placeholder key (e.g., `::phandaal/write`)
2. The `::s/description` string

This misses valuable searchable content:
- Parameter descriptions in Malli schemas (`:description` in map properties)
- Return value schemas and their descriptions
- Examples showing realistic usage
- Custom metadata like `:see-also` references

When a user searches for "threshold", they expect to find all effects that deal with thresholds - but if "threshold" only appears in a schema parameter description and not the top-level description, `grep` won't find it.

This forces API authors to duplicate concepts in prose descriptions, which is fragile and error-prone.

## Requirements

### Functional Requirements

1. `grep` must search all text content in the registration metadata
2. Must extract `:description` values from nested Malli schema properties
3. Must search `:returns` schema descriptions
4. Must search stringified `:examples` content
5. Must search values of custom user-defined keys
6. Must maintain current API: `(grep dispatch pattern)`

### Non-Functional Requirements

- Performance: Acceptable for interactive use (< 100ms for typical registries)
- Backward compatibility: Existing grep calls must continue to work
- No external dependencies beyond Malli (already required)

## Options Considered

### Option A: Inline text extraction in grep

**Description:** Add text extraction logic directly in the `grep` function.

```clojure
(defn grep [dispatch pattern]
  (let [all-items (describe dispatch :all)
        extract-text (fn [item]
                       (str (::s/key item) " "
                            (::s/description item) " "
                            (walk-schema (:returns item)) " "
                            ...))]
    (filter #(re-find pattern (extract-text %)) all-items)))
```

**Pros:**
- Simple, single-function change
- No new public API

**Cons:**
- Mixes concerns (search + extraction)
- Hard to test extraction logic in isolation
- Can't reuse extraction for other purposes

### Option B: Separate text extraction helper

**Description:** Create a `registration->searchable-text` helper function that `grep` calls.

```clojure
(defn- walk-schema-descriptions [schema]
  ...)

(defn- registration->searchable-text [item]
  (str/join " " [...]))

(defn grep [dispatch pattern]
  (filter #(re-find pattern (registration->searchable-text %)) all-items))
```

**Pros:**
- Clean separation of concerns
- Testable in isolation
- Reusable for future features (e.g., help system, docs generation)

**Cons:**
- Slightly more code

### Option C: Indexing at dispatch creation time

**Description:** Pre-compute searchable text when `create-dispatch` is called, store in registry.

**Pros:**
- Fastest grep performance
- Single extraction per effect

**Cons:**
- Adds complexity to dispatch creation
- Memory overhead storing redundant text
- Overkill for typical registry sizes

## Recommendation

**Option B: Separate text extraction helper**

The clean separation makes the code maintainable and testable. The performance difference vs Option A is negligible, and Option C is premature optimization for registries that typically have <100 effects.

## Open Questions

- [x] Should we search `:see-also` values? → Yes, include as searchable
- [x] Should we search example `:desc` fields? → Yes, descriptions are valuable
- [ ] Should we expose `registration->searchable-text` as public API? → TBD during implementation

## References

- Current grep implementation: `src/clj/ascolais/sandestin/describe.clj`
- Malli schema properties: https://github.com/metosin/malli#property-based-schemas
- Phandaal metadata example: `ascolais.phandaal/registry` with `:returns`, `:examples`
