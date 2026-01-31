# Continuous Context Flow for Interceptors - Implementation Plan

## Overview

Step-by-step implementation tasks for enabling continuous context flow through interceptor phases.

## Scope of Changes

### 1. dispatch.clj

Refactor to pass context through phases:

- `dispatch` - Build initial context, flow through phases
- `expand-actions-with-interceptors` - Receive/return context, add `:action` during expansion
- `execute-effects` - Receive/return context, add `:effect` during execution
- `execute-effect-with-interceptors` - Receive/return context
- `expand-action-with-interceptors` - Receive/return context

### 2. interceptors.clj

No changes needed - already designed around context maps.

## Phase 1: Core Refactor

- [x] Refactor `dispatch` to build initial context and flow through phases
- [x] Update `expand-actions-with-interceptors` to receive/return context
- [x] Update `execute-effects` to receive/return context
- [x] Update `execute-effect-with-interceptors` to receive/return context
- [x] Update `expand-action-with-interceptors` to receive/return context

## Phase 2: After-Phase Propagation

- [x] Ensure after-action sees `:actions` produced by handler
- [x] Ensure after-effect sees `:result` from effect handler
- [x] Ensure after-dispatch sees accumulated `:results`
- [x] Verify LIFO order for after-phase interceptors

## Phase 3: Testing

- [x] before-dispatch can modify dispatch-data, visible to effects
- [x] before-action can access and modify dispatch-data
- [x] before-effect modifications visible to subsequent effects
- [x] Modifications don't leak between separate dispatches
- [x] after-action sees :actions produced by handler
- [x] after-effect sees :result from effect handler
- [x] after-dispatch sees accumulated :results
- [x] After-phase modifications propagate to subsequent interceptors (LIFO order)
- [x] Clearing :queue/:stack in after-* aborts further processing

## Test Cases

```clojure
(deftest before-dispatch-can-modify-dispatch-data
  (let [interceptor {:before-dispatch
                     (fn [ctx]
                       (assoc-in ctx [:dispatch-data :injected] :by-interceptor))}

        effect-received (atom nil)

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/effects
                  {::capture
                   {:ascolais.sandestin/handler
                    (fn [{:keys [dispatch-data]} _]
                      (reset! effect-received dispatch-data))}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::capture]])

    (is (= :by-interceptor (:injected @effect-received))
        "Effect handler should see dispatch-data modified by before-dispatch interceptor")))

(deftest after-interceptors-run-in-lifo-order
  (let [call-order (atom [])
        interceptor-a {:id :a
                       :after-action #(do (swap! call-order conj :a) %)}
        interceptor-b {:id :b
                       :after-action #(do (swap! call-order conj :b) %)}
        interceptor-c {:id :c
                       :after-action #(do (swap! call-order conj :c) %)}

        registry {:ascolais.sandestin/interceptors [interceptor-a interceptor-b interceptor-c]
                  :ascolais.sandestin/actions
                  {::test {:ascolais.sandestin/handler (fn [_] [[::noop]])}}
                  :ascolais.sandestin/effects
                  {::noop {:ascolais.sandestin/handler (fn [_ _])}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::test]])

    (is (= [:c :b :a] @call-order)
        "after-action interceptors should run in reverse order (LIFO)")))
```

## Backward Compatibility

This is additive behavior. Existing interceptors that don't modify context keys will behave identically. The context structure adds keys but doesn't remove any.

## Performance

Passing a single context map is equivalent to passing multiple arguments. No performance impact expected.
