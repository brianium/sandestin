# Deep Grep - Research

## Problem Statement

The current `grep` function only searches two fields:
1. The effect/action/placeholder key (e.g., `::phandaal/write`)
2. The `::s/description` string

This misses valuable searchable content:
- Parameter descriptions in Malli schemas (`:description` in map properties)
- Library-provided metadata (e.g., `::phandaal/returns`, `::phandaal/examples`)
- Any other custom keys libraries add to registrations

When a user searches for "threshold", they expect to find all effects that deal with thresholds - but if "threshold" only appears in a schema parameter description and not the top-level description, `grep` won't find it.

This forces API authors to duplicate concepts in prose descriptions, which is fragile and error-prone.

## Library Metadata Convention

Sandestin core only defines a few keys:
- `::s/description` - Human-readable description
- `::s/schema` - Malli schema for invocation shape
- `::s/handler` - The effect/action handler function

Libraries are encouraged to add their own namespaced keys for richer metadata:

```clojure
;; Example from phandaal
{::s/description "Write content to a file..."
 ::s/schema [:tuple [:= ::write] [:map [:path :string] ...]]
 ::s/handler (make-write-handler opts)
 ;; Library-provided metadata
 ::phandaal/returns file-result-schema
 ::phandaal/examples [{:desc "Write a new file" :effect [...] :returns {...}}]}
```

Deep grep should search ALL non-handler keys without needing to know about specific libraries.

## Requirements

### Functional Requirements

1. `grep` must search all text content in the registration metadata
2. Must extract `:description` values from nested Malli schema properties
3. Must recursively walk and stringify all non-core keys (library metadata)
4. Must maintain current API: `(grep dispatch pattern)`

### Non-Functional Requirements

- Performance: Acceptable for interactive use (< 100ms for typical registries)
- Backward compatibility: Existing grep calls must continue to work
- No external dependencies beyond Malli (already required)
- No knowledge of specific library keys required (generic recursive extraction)

## Options Considered

### Option A: Inline text extraction in grep

**Description:** Add text extraction logic directly in the `grep` function.

```clojure
(defn grep [dispatch pattern]
  (let [all-items (describe dispatch :all)
        extract-text (fn [item]
                       (str (::s/key item) " "
                            (::s/description item) " "
                            (walk-schema-descriptions (::s/schema item)) " "
                            (extract-remaining-keys item)))]
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
  ;; Extract :description from Malli schema properties
  ...)

(defn- registration->searchable-text [item]
  (str/join " "
    [(::s/key item)
     (::s/description item)
     (walk-schema-descriptions (::s/schema item))
     ;; Recursively stringify ALL other keys (library metadata)
     (stringify-non-core-keys item)]))

(defn grep [dispatch pattern]
  (filter #(re-find pattern (registration->searchable-text %)) all-items))
```

**Pros:**
- Clean separation of concerns
- Testable in isolation
- Reusable for future features (e.g., help system, docs generation)
- Library-agnostic: searches everything without knowing specific keys

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
- Overkill for typical registry sizes (dev tool, not hot path)

## Recommendation

**Option B: Separate text extraction helper**

The clean separation makes the code maintainable and testable. The key insight is that `stringify-non-core-keys` walks ALL keys that aren't `::s/handler` (skip functions) or already processed core keys. This means libraries like phandaal can add `::phandaal/returns`, `::phandaal/examples`, or any other metadata and it's automatically searchable without sandestin needing to know about it.

## Open Questions

- [x] Should we search specific keys like `:returns`, `:examples`? → Search ALL non-core keys generically
- [x] Do we need `:see-also` as a special concept? → No, just search everything recursively
- [x] Should we expose `registration->searchable-text` as public API? → No, keep as private `defn-`

## References

- Current grep implementation: `src/clj/ascolais/sandestin/describe.clj`
- Malli schema properties: https://github.com/metosin/malli#property-based-schemas
- Phandaal library: example of library-provided metadata (`::phandaal/returns`, `::phandaal/examples`)
