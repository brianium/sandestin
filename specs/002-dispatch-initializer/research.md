# Dispatch Initializer Function - Research

## Problem Statement

Component systems like Integrant, Mount, and Component expect initialization functions that take configuration maps. Currently, users must call `create-dispatch` directly, which works but requires understanding the API.

A conventional `dispatch` initializer function:
1. Provides a familiar pattern for component system users
2. Makes configuration more declarative
3. Establishes a standard entry point for the library

## Options Considered

### Option A: Multimethod Pattern

**Description:** Define a default `ig/init-key` method for Integrant integration.

**Pros:**
- Zero-config for Integrant users
- Familiar pattern

**Cons:**
- Couples sandestin to integrant specifically
- Requires integrant as a dependency
- Doesn't help Mount/Component users

### Option B: Plain Function with Config Map

**Description:** Add a plain `dispatch` function that takes a config map.

**Pros:**
- Works with any component system
- No additional dependencies
- Simple implementation
- Extensible via config map

**Cons:**
- Users must define their own init-key (trivial)

## Recommendation

**Option B: Plain Function** - A plain function works with any component system without coupling to a specific one. Users can easily wrap it in their component system's conventions.

## Design Details

### Why `:registries` as the key name?

Matches the `create-dispatch` parameter name and is descriptive. Alternative considered: `:registry` (singular), but plural is accurate since it accepts multiple registries.

### Implementation

The implementation is minimal - a thin wrapper around `create-dispatch`:

```clojure
(defn dispatch
  "Initialize a dispatch function from configuration.

   Integrant-style initializer that creates a Dispatch from a config map.
   Designed for easy integration with component systems.

   Config keys:
   - :registries - Vector of registries (required), passed to create-dispatch

   Example:
     ;; Integrant config
     {:my-app/dispatch {:registries [[db/registry {:dbtype \"sqlite\"}]
                                     logger/registry]}}

     ;; Integrant init-key
     (defmethod ig/init-key :my-app/dispatch [_ config]
       (s/dispatch config))

   See create-dispatch for registry format details."
  [{:keys [registries]}]
  (create-dispatch registries))
```

## Backward Compatibility

This is purely additive. No existing API is changed.
