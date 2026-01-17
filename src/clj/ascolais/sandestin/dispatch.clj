(ns ascolais.sandestin.dispatch
  "Core dispatch implementation for Sandestin.

   Dispatch flow with interceptors:
   1. before-dispatch interceptors
   2. Interpolate placeholders in input
   3. Expand actions to effects (with before/after-action interceptors)
   4. Execute effects (with before/after-effect interceptors)
   5. after-dispatch interceptors"
  (:require [ascolais.sandestin.placeholders :as placeholders]
            [ascolais.sandestin.actions :as actions]
            [ascolais.sandestin.interceptors :as interceptors]))

(def ^:private max-action-depth
  "Maximum depth for action expansion to prevent infinite loops."
  100)

;; Forward declaration for mutual recursion
(declare dispatch)

;; =============================================================================
;; Effect Execution (with interceptors)
;; =============================================================================

(defn- execute-single-effect
  "Execute a single effect handler (the core execution, no interceptors)."
  [registry handler-ctx system effect]
  (let [[effect-key & args] effect
        effects-map (:ascolais.sandestin/effects registry)
        registration (get effects-map effect-key)]
    (if-not registration
      {:effect effect
       :err (ex-info "Unknown effect" {:effect-key effect-key
                                       :available-effects (keys effects-map)})}
      (try
        (let [handler (:ascolais.sandestin/handler registration)
              result (apply handler handler-ctx system args)]
          {:effect effect
           :res result})
        (catch Exception e
          {:effect effect
           :err e})))))

(defn- execute-effect-with-interceptors
  "Execute a single effect wrapped with before/after-effect interceptors.

   Receives the full context and returns updated context with accumulated
   results/errors and any modifications made by interceptors (including
   dispatch-data changes)."
  [registry interceptors ctx effect]
  (let [;; Add effect-specific key to context
        effect-ctx (assoc ctx :effect effect)

        ;; Run before-effect interceptors
        before-ctx (interceptors/run-before interceptors :effect effect-ctx)]

    (if (interceptors/halted? before-ctx)
      ;; Halted - skip execution, just run after interceptors
      (let [after-ctx (interceptors/run-after interceptors :effect before-ctx)]
        ;; Return context without the effect-specific key
        (dissoc after-ctx :effect :result))

      ;; Execute the effect - build handler-ctx from current context
      (let [handler-ctx {:dispatch (:dispatch before-ctx)
                         :dispatch-data (:dispatch-data before-ctx)
                         :system (:system before-ctx)}
            result (execute-single-effect registry handler-ctx (:system before-ctx) effect)
            ;; Update context with result
            exec-ctx (if (:err result)
                       (-> before-ctx
                           (update :errors conj
                                   {:phase :execute-effect
                                    :effect (:effect result)
                                    :err (:err result)})
                           (assoc :result nil))
                       (-> before-ctx
                           (update :results conj result)
                           (assoc :result (:res result))))
            ;; Run after-effect interceptors
            after-ctx (interceptors/run-after interceptors :effect exec-ctx)]
        ;; Return context without effect-specific keys
        (dissoc after-ctx :effect :result)))))

(defn- execute-effects
  "Execute a sequence of effects with interceptors.

   Receives a context map and executes each effect, threading the context
   through so modifications (including dispatch-data) propagate between effects."
  [registry interceptors ctx effects]
  (let [system (:system ctx)
        ;; Create dispatch function for async continuation
        ;; Note: This captures the initial dispatch-data. Each effect execution
        ;; will build its own handler-ctx from the current context state.
        ;; For continuations, we use the original dispatch-data as the base.
        initial-dispatch-data (:dispatch-data ctx)
        dispatch-fn (fn dispatch-continuation
                      ([fx]
                       (dispatch-continuation system {} fx))
                      ([extra-dispatch-data fx]
                       (dispatch-continuation system extra-dispatch-data fx))
                      ([system-override extra-dispatch-data fx]
                       (dispatch registry
                                 (merge system system-override)
                                 (merge initial-dispatch-data extra-dispatch-data)
                                 fx)))
        ;; Add dispatch function to context
        ctx-with-dispatch (assoc ctx :dispatch dispatch-fn)]
    ;; Execute each effect with interceptors, threading context through
    (reduce
     (fn [current-ctx effect]
       (execute-effect-with-interceptors registry interceptors current-ctx effect))
     ctx-with-dispatch
     effects)))

;; =============================================================================
;; Action Expansion (with interceptors)
;; =============================================================================

(defn- expand-single-action
  "Expand a single action (the core expansion, no interceptors)."
  [registry state action]
  (let [[action-key & args] action
        actions-map (:ascolais.sandestin/actions registry)
        registration (get actions-map action-key)]
    (if-not registration
      {:error {:phase :expand-action
               :action action
               :err (ex-info "Unknown action" {:action-key action-key
                                               :available-actions (keys actions-map)})}}
      (try
        (let [handler (:ascolais.sandestin/handler registration)
              expanded (apply handler state args)]
          {:expanded (vec expanded)})
        (catch Exception e
          {:error {:phase :expand-action
                   :action action
                   :err e}})))))

(defn- expand-action-with-interceptors
  "Expand a single action wrapped with before/after-action interceptors.

   Receives the full context (including dispatch-data) and returns updated
   context with expanded actions and any modifications made by interceptors."
  [registry interceptors ctx action]
  (let [;; Add action-specific key to context
        action-ctx (assoc ctx :action action)

        ;; Run before-action interceptors
        before-ctx (interceptors/run-before interceptors :action action-ctx)]

    (if (interceptors/halted? before-ctx)
      ;; Halted - skip expansion, just run after interceptors
      (let [after-ctx (interceptors/run-after interceptors :action
                                              (assoc before-ctx :actions []))]
        ;; Return context with empty actions, without action-specific key
        (-> after-ctx
            (assoc :expanded [])
            (dissoc :action :actions)))

      ;; Expand the action
      (let [{:keys [expanded error]} (expand-single-action registry (:state before-ctx) action)
            ;; Update context with expansion result
            exec-ctx (if error
                       (-> before-ctx
                           (update :errors conj error)
                           (assoc :actions []))
                       (assoc before-ctx :actions expanded))
            ;; Run after-action interceptors
            after-ctx (interceptors/run-after interceptors :action exec-ctx)]
        ;; Return context with expanded actions
        (-> after-ctx
            (assoc :expanded (or (:actions after-ctx) []))
            (dissoc :action :actions))))))

(defn- expand-actions-with-interceptors
  "Recursively expand actions until only effects remain, with interceptors.
   Interpolates placeholders between each action expansion round.

   Receives and returns a context map, threading dispatch-data modifications
   through the expansion process. The :effects key accumulates the final
   flattened list of effects."
  [registry interceptors ctx actions-or-effects max-depth]
  (let [placeholders-map (:ascolais.sandestin/placeholders registry)]
    (if (zero? max-depth)
      (update ctx :errors conj {:phase :expand-action
                                :err (ex-info "Max action expansion depth reached" {})})
      (reduce
       (fn [current-ctx item]
         (let [[item-key] item]
           (cond
             ;; It's an effect - pass through, accumulate in :effects
             (actions/effect? registry item-key)
             (update current-ctx :effects conj item)

             ;; It's an action - expand with interceptors
             (actions/action? registry item-key)
             (let [result-ctx (expand-action-with-interceptors
                               registry interceptors current-ctx item)
                   expanded (:expanded result-ctx)]
               (if (seq expanded)
                 ;; Interpolate placeholders after expansion (Nexus 2025.10.1 behavior)
                 ;; This allows actions to introduce placeholders that get resolved
                 (let [interpolated-expanded (if (seq placeholders-map)
                                               (placeholders/interpolate
                                                placeholders-map
                                                (:dispatch-data result-ctx)
                                                expanded)
                                               expanded)
                       ;; Recursively expand the result, using updated context
                       ;; Note: we pass the current effects forward so they accumulate
                       recursive-ctx (expand-actions-with-interceptors
                                      registry interceptors
                                      (dissoc result-ctx :expanded)
                                      interpolated-expanded
                                      (dec max-depth))]
                   recursive-ctx)
                 ;; No expanded actions, just return updated context
                 (dissoc result-ctx :expanded)))

             ;; Unknown - error
             :else
             (update current-ctx :errors conj
                     {:phase :expand-action
                      :action item
                      :err (ex-info "Unknown action or effect"
                                    {:key item-key})}))))
       ;; Initialize effects vector only if not already present
       ;; This allows recursive calls to accumulate into existing effects
       (if (contains? ctx :effects)
         ctx
         (assoc ctx :effects []))
       actions-or-effects))))

;; =============================================================================
;; Main Dispatch
;; =============================================================================

(defn dispatch
  "Dispatch actions/effects using the given registry.

   Flow:
   1. before-dispatch interceptors
   2. Interpolate placeholders in the input
   3. Expand actions to effects (with before/after-action interceptors)
   4. Execute effects (with before/after-effect interceptors)
   5. after-dispatch interceptors

   Context flows continuously through all phases. Modifications made by
   interceptors (including to :dispatch-data) propagate to subsequent phases.

   Arguments:
   - registry: A merged registry map
   - system: The live system (passed to effect handlers)
   - dispatch-data: Data available to handlers and placeholders
   - actions-or-effects: A vector of action/effect vectors

   Returns {:results [...] :errors [...]}"
  [registry system dispatch-data actions-or-effects]
  (let [interceptors (or (:ascolais.sandestin/interceptors registry) [])
        placeholders-map (:ascolais.sandestin/placeholders registry)
        state (actions/get-state registry system)

        ;; Build initial dispatch context
        initial-ctx {:system system
                     :state state
                     :dispatch-data dispatch-data
                     :actions actions-or-effects
                     :results []
                     :errors []}

        ;; Run before-dispatch interceptors
        before-ctx (interceptors/run-before interceptors :dispatch initial-ctx)]

    (if (interceptors/halted? before-ctx)
      ;; Halted before any work - run after-dispatch and return
      (let [after-ctx (interceptors/run-after interceptors :dispatch before-ctx)]
        {:results (:results after-ctx)
         :errors (:errors after-ctx)})

      ;; Normal flow - context flows through all phases
      (let [;; Step 1: Interpolate placeholders using current dispatch-data
            ;; (which may have been modified by before-dispatch interceptors)
            interpolated (if (seq placeholders-map)
                           (placeholders/interpolate-effects
                            placeholders-map (:dispatch-data before-ctx) (:actions before-ctx))
                           (:actions before-ctx))

            ;; Step 2: Expand actions to effects (with interceptors)
            ;; Context flows through, dispatch-data modifications propagate
            expand-ctx (expand-actions-with-interceptors
                        registry interceptors before-ctx interpolated max-action-depth)

            ;; Step 3: Execute effects (with interceptors)
            ;; Context continues to flow, dispatch-data modifications propagate
            exec-ctx (execute-effects
                      registry interceptors expand-ctx (:effects expand-ctx))

            ;; Step 4: Run after-dispatch interceptors
            after-ctx (interceptors/run-after interceptors :dispatch exec-ctx)]

        {:results (:results after-ctx)
         :errors (:errors after-ctx)}))))
