---
title: "Dispatch Initializer Function"
status: completed
date: 2024-01-15
priority: 20
---

# Dispatch Initializer Function

## Overview

Add a `dispatch` function to the main sandestin namespace that serves as an integrant-style initializer. This provides a convenient entry point for component systems.

## Goals

- Provide a familiar pattern for component system users (Integrant, Mount, Component)
- Make configuration more declarative via config maps
- Establish a standard entry point for the library

## Non-Goals

- Coupling sandestin to any specific component system
- Adding integrant as a dependency

## Key Decisions

See [research.md](research.md) for detailed analysis.

| Decision | Choice | Rationale |
|----------|--------|-----------|
| API style | Config map with `:registries` key | Matches component system conventions |
| Key name | `:registries` (plural) | Matches `create-dispatch` param, accurate |
| Implementation | Thin wrapper around `create-dispatch` | Minimal, no new logic |

## Implementation Status

See [implementation-plan.md](implementation-plan.md) for detailed task breakdown.

- [x] Phase 1: Add dispatch function to sandestin.clj
- [x] Phase 2: Testing
- [x] Phase 3: Documentation

## Usage

```clojure
(require '[ascolais.sandestin :as s])

;; Initializer takes a config map with :registries key
(def my-dispatch
  (s/dispatch {:registries [[db/registry {:dbtype "sqlite"}]
                            logger/registry
                            my-app/registry]}))

;; The returned dispatch works exactly like create-dispatch result
(my-dispatch system dispatch-data effects)
```

### Configuration Map

| Key | Type | Description |
|-----|------|-------------|
| `:registries` | vector | Sequence of registries, passed directly to `create-dispatch` |

### Future Extensibility

The config map pattern allows adding future options without breaking changes:

```clojure
;; Potential future options
(s/dispatch {:registries [...]
             :validate? true           ;; validate schemas on load
             :default-interceptors []  ;; additional interceptors
             })
```
