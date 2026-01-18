# Spec 002: Dispatch Initializer Function

**Status:** Complete

## Summary

Add a `dispatch` function to the main sandestin namespace that serves as an integrant-style initializer. This provides a convenient entry point for component systems.

## Motivation

Component systems like Integrant, Mount, and Component expect initialization functions that take configuration maps. Currently, users must call `create-dispatch` directly, which works but requires understanding the API.

A conventional `dispatch` initializer function:
1. Provides a familiar pattern for component system users
2. Makes configuration more declarative
3. Establishes a standard entry point for the library

## Proposed API

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

## Implementation

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

## Design Decisions

### Why not use a multimethod pattern?

Integrant uses multimethods (`ig/init-key`) for initialization. We could define a default init-key method, but this would:
1. Couple sandestin to integrant specifically
2. Require integrant as a dependency

Instead, a plain function works with any component system.

### Why `:registries` as the key name?

Matches the `create-dispatch` parameter name and is descriptive. Alternative considered: `:registry` (singular), but plural is accurate since it accepts multiple registries.

### Future extensibility

The config map pattern allows adding future options without breaking changes:

```clojure
;; Potential future options (not in this spec)
(s/dispatch {:registries [...]
             :validate? true           ;; validate schemas on load
             :default-interceptors []  ;; additional interceptors
             })
```

## Scope of Changes

### src/clj/ascolais/sandestin.clj

Add the `dispatch` function after `create-dispatch`:

```clojure
(defn dispatch
  "Initialize a dispatch function from configuration.
   ..."
  [{:keys [registries]}]
  (create-dispatch registries))
```

### Tests

```clojure
(deftest dispatch-initializer
  (testing "creates dispatch from config map"
    (let [registry {:ascolais.sandestin/effects
                    {::noop {:ascolais.sandestin/handler (fn [_ _] :ok)}}}
          d (s/dispatch {:registries [registry]})]
      (is (instance? ascolais.sandestin.Dispatch d))
      (is (= [{:effect [::noop] :res :ok}]
             (:results (d {} {} [[::noop]]))))))

  (testing "works with multiple registries"
    (let [reg-a {:ascolais.sandestin/effects
                 {::fx-a {:ascolais.sandestin/handler (fn [_ _] :a)}}}
          reg-b {:ascolais.sandestin/effects
                 {::fx-b {:ascolais.sandestin/handler (fn [_ _] :b)}}}
          d (s/dispatch {:registries [reg-a reg-b]})]
      (is (= :a (:res (first (:results (d {} {} [[::fx-a]]))))))
      (is (= :b (:res (first (:results (d {} {} [[::fx-b]]))))))))

  (testing "supports registry function vectors"
    (let [make-registry (fn [value]
                          {:ascolais.sandestin/effects
                           {::configured {:ascolais.sandestin/handler
                                          (fn [_ _] value)}}})
          d (s/dispatch {:registries [[make-registry :configured-value]]})]
      (is (= :configured-value
             (:res (first (:results (d {} {} [[::configured]])))))))))
```

## Backward Compatibility

This is purely additive. No existing API is changed.

## Version

Minor release (0.5.0) - new feature, no breaking changes.
