# Continuous Context Flow for Interceptors - Research

## Problem Statement

Context modifications made by interceptors are discarded between phases. The dispatch flow rebuilds context at each phase boundary, losing modifications from prior phases. This prevents interceptors from enriching the context that flows through the system.

## Current Behavior (Before Fix)

In `dispatch.clj`, context was rebuilt at each phase:

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

This meant:
1. `before-dispatch` modifies `ctx[:dispatch-data]` → discarded
2. `before-action` cannot access `dispatch-data` at all
3. `before-effect` sees `dispatch-data` but modifications aren't propagated to next effect

## Options Considered

### Option A: Explicit Key Propagation

**Description:** Manually propagate specific keys (like `:dispatch-data`) between phases.

**Pros:**
- Minimal changes
- Explicit control over what propagates

**Cons:**
- Must update code for each new key
- Easy to miss propagation points
- Doesn't scale

### Option B: Continuous Context Flow (Nexus Pattern)

**Description:** Pass a single context map through all phases, enriching it with phase-specific keys.

**Pros:**
- All modifications automatically propagate
- Aligns with Nexus design
- Simpler mental model
- No special-casing needed

**Cons:**
- More extensive refactor
- Context accumulates keys (minor memory)

## Recommendation

**Option B: Continuous Context Flow** - This aligns Sandestin's interceptor model with Nexus where context flows continuously and all modifications accumulate and propagate.

## Design Details

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

### After-Phase Value Propositions

Context propagation through after-* phases enables important patterns:

#### 1. Observability

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

```clojure
(defn abort-on-error [ctx]
  (if (seq (:errors ctx))
    (dissoc ctx :queue :stack :actions :effect)  ; halt all processing
    ctx))

{:after-action abort-on-error
 :after-effect abort-on-error}
```

#### 3. Result Transformation

```clojure
{:after-action
 (fn [ctx]
   ;; Add audit effect to every action's expansion
   (update ctx :actions conj [:audit/log (:action ctx)]))}
```

## References

- [Nexus](https://github.com/brianium/nexus) - Inspiration for continuous context flow
- `src/clj/ascolais/sandestin/dispatch.clj` - Main dispatch implementation
- `src/clj/ascolais/sandestin/interceptors.clj` - Interceptor chain implementation
