(ns ascolais.sandestin.interceptors
  "Interceptor chain implementation for Sandestin dispatch.

   Interceptors provide lifecycle hooks for instrumenting dispatch:
   - before-dispatch / after-dispatch
   - before-action / after-action
   - before-effect / after-effect

   Before handlers run in order (queue), after handlers run in reverse (stack).")

(defn run-before
  "Run before-phase interceptors in order.

   Each interceptor's before-fn receives and returns the context.
   Errors are caught and added to context's :errors."
  [interceptors phase ctx]
  (let [phase-key (keyword (str "before-" (name phase)))]
    (reduce
     (fn [ctx interceptor]
       (if-let [before-fn (get interceptor phase-key)]
         (try
           (before-fn ctx)
           (catch Exception e
             (update ctx :errors conj
                     {:phase (keyword "interceptor" (str "before-" (name phase)))
                      :interceptor-id (:id interceptor)
                      :err e})))
         ctx))
     ctx
     interceptors)))

(defn run-after
  "Run after-phase interceptors in reverse order (stack).

   Each interceptor's after-fn receives and returns the context.
   Errors are caught and added to context's :errors."
  [interceptors phase ctx]
  (let [phase-key (keyword (str "after-" (name phase)))]
    (reduce
     (fn [ctx interceptor]
       (if-let [after-fn (get interceptor phase-key)]
         (try
           (after-fn ctx)
           (catch Exception e
             (update ctx :errors conj
                     {:phase (keyword "interceptor" (str "after-" (name phase)))
                      :interceptor-id (:id interceptor)
                      :err e})))
         ctx))
     ctx
     (reverse interceptors))))

(defn wrap-phase
  "Wrap a phase execution with before/after interceptors.

   Arguments:
   - interceptors: Vector of interceptor maps
   - phase: Keyword like :dispatch, :action, :effect
   - ctx: Current context
   - execute-fn: Function that executes the phase, receives ctx returns ctx

   Returns updated context after before -> execute -> after."
  [interceptors phase ctx execute-fn]
  (-> ctx
      (run-before interceptors phase)
      execute-fn
      (run-after interceptors phase)))

;; =============================================================================
;; Built-in Interceptors
;; =============================================================================

(def fail-fast
  "Interceptor that stops execution on first error.

   Add to your registry's ::interceptors to enable fail-fast behavior.
   When an error is detected in the :errors vector, clears remaining
   work to stop processing."
  {:id :ascolais.sandestin.interceptors/fail-fast

   :before-dispatch
   (fn [ctx]
     (if (seq (:errors ctx))
       (assoc ctx ::halt true)
       ctx))

   :after-dispatch
   (fn [ctx] ctx)

   :before-action
   (fn [ctx]
     (if (seq (:errors ctx))
       (assoc ctx ::halt true)
       ctx))

   :after-action
   (fn [ctx] ctx)

   :before-effect
   (fn [ctx]
     (if (seq (:errors ctx))
       (assoc ctx ::halt true)
       ctx))

   :after-effect
   (fn [ctx] ctx)})

(defn halted?
  "Check if processing should halt (for fail-fast support)."
  [ctx]
  (::halt ctx))
