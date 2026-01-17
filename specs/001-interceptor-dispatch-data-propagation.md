# Spec 001: Continuous Context Flow for Interceptors

**Status:** Complete

## Problem Statement

Context modifications made by interceptors are discarded between phases. The dispatch flow rebuilds context at each phase boundary, losing modifications from prior phases. This prevents interceptors from enriching the context that flows through the system.

## Current Behavior

In `dispatch.clj`, context is rebuilt at each phase:

```clojure
;; dispatch.clj - context rebuilt at action phase (lines 135-138)
(let [action-ctx {:state state
                  :action action
                  :errors errors}
      ;; ... dispatch-data not included, prior context lost

;; dispatch.clj - context rebuilt at effect phase (lines 47-53)
(let [effect-ctx {:system system
                  :dispatch-data (:dispatch-data handler-ctx)  ; from handler-ctx, not prior phase
                  :dispatch (:dispatch handler-ctx)
                  :effect effect
                  :results results
                  :errors errors}
```

And the main dispatch flow (line 270):
```clojure
;; execute-effects receives original dispatch-data, not before-ctx's
(execute-effects registry interceptors system dispatch-data effects errors)
```

This means:
1. `before-dispatch` modifies `ctx[:dispatch-data]` → discarded
2. `before-action` cannot access `dispatch-data` at all
3. `before-effect` sees `dispatch-data` but modifications aren't propagated to next effect

## Motivation: SSE Connection Reuse

Discovered while building [sfere](https://github.com/brianium/sfere), a connection management library for twk/Datastar applications.

**The scenario:**
1. twk dispatches `[[:ascolais.twk/connection]]` to look for an existing SSE connection
2. sfere's interceptor should inject a stored connection into `dispatch-data`
3. twk's `::twk/connection` effect handler reads from `dispatch-data` to return the connection

**What happens now:**
1. sfere's `before-dispatch` interceptor sets `ctx[:dispatch-data ::twk/connection]`
2. Context is rebuilt; twk's effect handler receives original `dispatch-data`
3. Effect returns `nil`, twk creates a new SSE instead of reusing

## Proposed Solution: Continuous Context Flow

Follow the [Nexus](https://github.com/brianium/nexus) pattern where context flows continuously through all phases. Modifications accumulate naturally without explicit propagation of individual keys.

### Design Principles

1. **Single context flows through entire dispatch** - no rebuilding at phase boundaries
2. **Each phase enriches the context** - adds phase-specific keys (`:action`, `:effect`, etc.)
3. **Interceptors see accumulated context** - all prior modifications visible
4. **No special-casing** - new context keys automatically propagate
5. **After phases propagate too** - modifications in after-* interceptors flow to subsequent interceptors

### Interceptor Execution Order

Following Nexus, interceptors use a **queue and stack pattern**:

1. **Before phase (queue)**: Interceptors execute sequentially, each moving from queue to stack
2. **Handler phase**: The action/effect handler executes
3. **After phase (stack)**: Interceptors execute in **reverse order** (LIFO)

```
BEFORE PHASE (forward through interceptors):
  Interceptor 1 before → Interceptor 2 before → Interceptor 3 before
        ↓                      ↓                       ↓
      ctx1 →                 ctx2 →                  ctx3 →

  HANDLER EXECUTES (produces :actions or effect result)

AFTER PHASE (reverse order):
      ← ctx3'               ← ctx2'               ← ctx1'
  Interceptor 3 after ← Interceptor 2 after ← Interceptor 1 after
```

This means:
- Before interceptors can set up context that inner interceptors and handlers see
- After interceptors closest to the handler see results first
- Outer after interceptors can observe/transform what inner interceptors did

### Context Structure

The context accumulates keys as it flows through phases:

```clojure
;; before-dispatch receives
{:system system
 :state state
 :dispatch-data dispatch-data  ; can be modified by interceptors
 :actions actions-or-effects
 :results []
 :errors []}

;; after-dispatch receives (after all processing complete)
{:system system
 :state state
 :dispatch-data dispatch-data
 :actions [...]
 :results [{:effect [...] :res result} ...]  ; effect results accumulated
 :errors [...]}

;; before-action receives
{:system system
 :state state
 :dispatch-data dispatch-data  ; preserved from prior phase
 :actions [...]
 :action current-action        ; added for this phase
 :results []
 :errors []}

;; after-action receives
{:system system
 :state state
 :dispatch-data dispatch-data
 :action original-action       ; the action that was expanded
 :actions [...]                ; effects/actions PRODUCED by handler
 :results []
 :errors [...]}

;; before-effect receives
{:system system
 :state state
 :dispatch-data dispatch-data  ; preserved from prior phases
 :dispatch dispatch-fn         ; added for effects
 :effect current-effect        ; added for this phase
 :results [...]
 :errors [...]}

;; after-effect receives
{:system system
 :state state
 :dispatch-data dispatch-data
 :dispatch dispatch-fn
 :effect effect-that-ran
 :result effect-return-value   ; the return value from the effect handler
 :results [...]                ; accumulated results
 :errors [...]}
```

### Implementation Approach

Rather than passing individual values to helper functions, pass and return the full context:

```clojure
;; Current approach - passes individual values
(execute-effects registry interceptors system dispatch-data effects errors)

;; Proposed approach - passes context
(execute-effects registry interceptors ctx)
;; Where ctx contains :system, :dispatch-data, :effects, :errors, etc.
```

Each function:
1. Receives the current context
2. Adds phase-specific keys if needed
3. Runs interceptors (which may modify context)
4. Returns updated context

### After-Phase Value Propositions

Context propagation through after-* phases enables important patterns:

#### 1. Observability

Interceptors can inspect handler results for logging, metrics, or debugging:

```clojure
{:after-action
 (fn [ctx]
   (log/info "Action" (:action ctx) "produced" (count (:actions ctx)) "effects")
   ctx)

 :after-effect
 (fn [ctx]
   (tap> {:effect (:effect ctx) :result (:result ctx)})
   ctx)}
```

#### 2. Flow Control

Interceptors can abort processing by clearing the queue/stack (following Nexus's `fail-fast` pattern):

```clojure
(defn abort-on-error [ctx]
  (if (seq (:errors ctx))
    (dissoc ctx :queue :stack :actions :effect)  ; halt all processing
    ctx))

{:after-action abort-on-error
 :after-effect abort-on-error}
```

#### 3. Result Transformation

After interceptors can modify results before outer interceptors see them:

```clojure
{:after-action
 (fn [ctx]
   ;; Add audit effect to every action's expansion
   (update ctx :actions conj [:audit/log (:action ctx)]))}
```

#### 4. Cross-Interceptor Communication

Earlier interceptors (in LIFO order) can set context keys that later interceptors read:

```clojure
;; Interceptor A (runs second in after-action due to LIFO)
{:after-action (fn [ctx] (assoc ctx :timing (- (now) (:start-time ctx))))}

;; Interceptor B (runs first in after-action, closest to handler)
{:before-action (fn [ctx] (assoc ctx :start-time (now)))
 :after-action (fn [ctx] ctx)}  ; timing not yet available

;; Interceptor C (runs third in after-action, outermost)
{:after-action (fn [ctx] (record-metric! (:timing ctx)) ctx)}  ; sees timing
```

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

### 3. Tests

Add test cases verifying:

**Before-phase propagation:**
- `before-dispatch` can modify `dispatch-data`, visible to effects
- `before-action` can access and modify `dispatch-data`
- `before-effect` modifications visible to subsequent effects
- Modifications don't leak between separate dispatches

**After-phase propagation:**
- `after-action` sees `:actions` produced by handler
- `after-effect` sees `:result` from effect handler
- `after-dispatch` sees accumulated `:results`
- After-phase modifications propagate to subsequent interceptors (LIFO order)

**Flow control:**
- Clearing `:queue`/`:stack` in after-* aborts further processing

## Considerations

### Backward Compatibility

This is additive behavior. Existing interceptors that don't modify context keys will behave identically. The context structure adds keys but doesn't remove any.

### Performance

Passing a single context map is equivalent to passing multiple arguments. No performance impact expected.

### Nexus Alignment

This aligns Sandestin's interceptor model with Nexus:
- Context flows continuously
- Interceptors are pure functions: `ctx -> ctx`
- All modifications accumulate and propagate

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

(deftest before-action-can-access-dispatch-data
  (let [seen-dispatch-data (atom nil)
        interceptor {:before-action
                     (fn [ctx]
                       (reset! seen-dispatch-data (:dispatch-data ctx))
                       ctx)}

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/actions
                  {::test-action
                   {:ascolais.sandestin/handler
                    (fn [_state] [[::noop]])}}
                  :ascolais.sandestin/effects
                  {::noop
                   {:ascolais.sandestin/handler (fn [_ _])}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {:my-key :my-value} [[::test-action]])

    (is (= {:my-key :my-value} @seen-dispatch-data)
        "before-action interceptor should see dispatch-data")))

(deftest before-effect-modifications-propagate
  (let [call-order (atom [])
        interceptor {:before-effect
                     (fn [ctx]
                       (swap! call-order conj (:dispatch-data ctx))
                       (update-in ctx [:dispatch-data :counter] (fnil inc 0)))}

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/effects
                  {::noop {:ascolais.sandestin/handler (fn [_ _])}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::noop] [::noop] [::noop]])

    (is (= [{} {:counter 1} {:counter 2}] @call-order)
        "Each before-effect should see prior effect's modifications")))

;;; After-phase tests

(deftest after-action-sees-produced-actions
  (let [seen-actions (atom nil)
        interceptor {:after-action
                     (fn [ctx]
                       (reset! seen-actions (:actions ctx))
                       ctx)}

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/actions
                  {::produce-effects
                   {:ascolais.sandestin/handler
                    (fn [_state]
                      [[::effect-a] [::effect-b]])}}
                  :ascolais.sandestin/effects
                  {::effect-a {:ascolais.sandestin/handler (fn [_ _])}
                   ::effect-b {:ascolais.sandestin/handler (fn [_ _])}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::produce-effects]])

    (is (= [[::effect-a] [::effect-b]] @seen-actions)
        "after-action should see effects produced by action handler")))

(deftest after-effect-sees-result
  (let [seen-result (atom nil)
        interceptor {:after-effect
                     (fn [ctx]
                       (reset! seen-result (:result ctx))
                       ctx)}

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/effects
                  {::return-value
                   {:ascolais.sandestin/handler
                    (fn [_ _] {:status :ok :data 42})}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::return-value]])

    (is (= {:status :ok :data 42} @seen-result)
        "after-effect should see return value from effect handler")))

(deftest after-dispatch-sees-accumulated-results
  (let [seen-results (atom nil)
        interceptor {:after-dispatch
                     (fn [ctx]
                       (reset! seen-results (:results ctx))
                       ctx)}

        registry {:ascolais.sandestin/interceptors [interceptor]
                  :ascolais.sandestin/effects
                  {::fx-a {:ascolais.sandestin/handler (fn [_ _] :result-a)}
                   ::fx-b {:ascolais.sandestin/handler (fn [_ _] :result-b)}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::fx-a] [::fx-b]])

    (is (= [{:effect [::fx-a] :res :result-a}
            {:effect [::fx-b] :res :result-b}]
           @seen-results)
        "after-dispatch should see all effect results")))

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

(deftest after-phase-modifications-propagate-between-interceptors
  (let [seen-by-outer (atom nil)
        inner-interceptor {:id :inner
                           :after-action
                           (fn [ctx]
                             (assoc ctx :added-by-inner :hello))}
        outer-interceptor {:id :outer
                           :after-action
                           (fn [ctx]
                             (reset! seen-by-outer (:added-by-inner ctx))
                             ctx)}

        ;; outer is first in vector, so runs last in after-action (LIFO)
        registry {:ascolais.sandestin/interceptors [outer-interceptor inner-interceptor]
                  :ascolais.sandestin/actions
                  {::test {:ascolais.sandestin/handler (fn [_] [[::noop]])}}
                  :ascolais.sandestin/effects
                  {::noop {:ascolais.sandestin/handler (fn [_ _])}}}

        dispatch (s/create-dispatch [registry])]

    (dispatch {} {} [[::test]])

    (is (= :hello @seen-by-outer)
        "Outer after-interceptor should see modifications from inner")))
```

## References

- [Nexus](https://github.com/brianium/nexus) - Inspiration for continuous context flow
- `src/clj/ascolais/sandestin/dispatch.clj` - Main dispatch implementation
- `src/clj/ascolais/sandestin/interceptors.clj` - Interceptor chain implementation
