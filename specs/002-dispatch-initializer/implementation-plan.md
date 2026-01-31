# Dispatch Initializer Function - Implementation Plan

## Overview

Step-by-step implementation tasks for adding the dispatch initializer function.

## Phase 1: Add Function

- [x] Add `dispatch` function to `src/clj/ascolais/sandestin.clj`
- [x] Add docstring with examples

## Phase 2: Testing

- [x] Test creates dispatch from config map
- [x] Test works with multiple registries
- [x] Test supports registry function vectors

### Test Cases

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

## Phase 3: Documentation

- [x] Update README with usage example (if applicable)
- [x] Document version bump (0.5.0)

## Version

Minor release (0.5.0) - new feature, no breaking changes.
