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
  "Execute a single effect wrapped with before/after-effect interceptors."
  [registry interceptors handler-ctx system effect results errors]
  (let [;; Build interceptor context for this effect
        effect-ctx {:system system
                    :dispatch-data (:dispatch-data handler-ctx)
                    :dispatch (:dispatch handler-ctx)
                    :effect effect
                    :results results
                    :errors errors}

        ;; Run before-effect interceptors
        before-ctx (interceptors/run-before interceptors :effect effect-ctx)]

    (if (interceptors/halted? before-ctx)
      ;; Halted - skip execution, just run after interceptors
      (let [after-ctx (interceptors/run-after interceptors :effect before-ctx)]
        {:results (:results after-ctx)
         :errors (:errors after-ctx)})

      ;; Execute the effect
      (let [result (execute-single-effect registry handler-ctx system effect)
            ;; Update context with result
            exec-ctx (if (:err result)
                       (update before-ctx :errors conj
                               {:phase :execute-effect
                                :effect (:effect result)
                                :err (:err result)})
                       (update before-ctx :results conj result))
            ;; Run after-effect interceptors
            after-ctx (interceptors/run-after interceptors :effect exec-ctx)]
        {:results (:results after-ctx)
         :errors (:errors after-ctx)}))))

(defn- execute-effects
  "Execute a sequence of effects with interceptors."
  [registry interceptors system dispatch-data effects initial-errors]
  (let [;; Create dispatch function for async continuation
        ;; Supports three arities:
        ;; - ([fx]) dispatch with current system and dispatch-data
        ;; - ([extra-dispatch-data fx]) dispatch with merged dispatch-data, same system
        ;; - ([system-override extra-dispatch-data fx]) dispatch with merged system and dispatch-data
        dispatch-fn (fn dispatch-continuation
                      ([fx]
                       (dispatch-continuation system {} fx))
                      ([extra-dispatch-data fx]
                       (dispatch-continuation system extra-dispatch-data fx))
                      ([system-override extra-dispatch-data fx]
                       (dispatch registry
                                 (merge system system-override)
                                 (merge dispatch-data extra-dispatch-data)
                                 fx)))
        ;; Build context for effect handlers
        handler-ctx {:dispatch dispatch-fn
                     :dispatch-data dispatch-data
                     :system system}]
    ;; Execute each effect with interceptors
    (reduce
     (fn [{:keys [results errors]} effect]
       (execute-effect-with-interceptors
        registry interceptors handler-ctx system effect results errors))
     {:results [] :errors initial-errors}
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
  "Expand a single action wrapped with before/after-action interceptors."
  [registry interceptors state action errors]
  (let [;; Build interceptor context for this action
        action-ctx {:state state
                    :action action
                    :errors errors}

        ;; Run before-action interceptors
        before-ctx (interceptors/run-before interceptors :action action-ctx)]

    (if (interceptors/halted? before-ctx)
      ;; Halted - skip expansion, just run after interceptors
      (let [after-ctx (interceptors/run-after interceptors :action
                                              (assoc before-ctx :actions []))]
        {:expanded []
         :errors (:errors after-ctx)})

      ;; Expand the action
      (let [{:keys [expanded error]} (expand-single-action registry state action)
            ;; Update context with expansion result
            exec-ctx (if error
                       (-> before-ctx
                           (update :errors conj error)
                           (assoc :actions []))
                       (assoc before-ctx :actions expanded))
            ;; Run after-action interceptors
            after-ctx (interceptors/run-after interceptors :action exec-ctx)]
        {:expanded (or (:actions after-ctx) [])
         :errors (:errors after-ctx)}))))

(defn- expand-actions-with-interceptors
  "Recursively expand actions until only effects remain, with interceptors.
   Interpolates placeholders between each action expansion round."
  [registry interceptors dispatch-data state actions-or-effects max-depth]
  (let [placeholders-map (:ascolais.sandestin/placeholders registry)]
    (if (zero? max-depth)
      {:effects []
       :errors [{:phase :expand-action
                 :err (ex-info "Max action expansion depth reached" {})}]}
      (reduce
       (fn [{:keys [effects errors]} item]
         (let [[item-key] item]
           (cond
             ;; It's an effect - pass through
             (actions/effect? registry item-key)
             {:effects (conj effects item)
              :errors errors}

             ;; It's an action - expand with interceptors
             (actions/action? registry item-key)
             (let [{:keys [expanded errors]}
                   (expand-action-with-interceptors registry interceptors state item errors)]
               (if (seq expanded)
                 ;; Interpolate placeholders after expansion (Nexus 2025.10.1 behavior)
                 ;; This allows actions to introduce placeholders that get resolved
                 (let [interpolated-expanded (if (seq placeholders-map)
                                               (placeholders/interpolate
                                                placeholders-map dispatch-data expanded)
                                               expanded)
                       ;; Recursively expand the result
                       recursive-result
                       (expand-actions-with-interceptors
                        registry interceptors dispatch-data state
                        interpolated-expanded (dec max-depth))]
                   {:effects (into effects (:effects recursive-result))
                    :errors (into errors (:errors recursive-result))})
                 {:effects effects
                  :errors errors}))

             ;; Unknown - error
             :else
             {:effects effects
              :errors (conj errors {:phase :expand-action
                                    :action item
                                    :err (ex-info "Unknown action or effect"
                                                  {:key item-key})})})))
       {:effects [] :errors []}
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

      ;; Normal flow
      (let [;; Step 1: Interpolate placeholders
            interpolated (if (seq placeholders-map)
                           (placeholders/interpolate-effects
                            placeholders-map dispatch-data actions-or-effects)
                           actions-or-effects)

            ;; Step 2: Expand actions to effects (with interceptors)
            ;; Placeholders are also interpolated between each action expansion
            {:keys [effects errors]}
            (expand-actions-with-interceptors
             registry interceptors dispatch-data state interpolated max-action-depth)

            ;; Step 3: Execute effects (with interceptors)
            exec-result (execute-effects
                         registry interceptors system dispatch-data effects errors)

            ;; Build final context for after-dispatch
            ;; Merge errors from before-dispatch with errors from execution
            final-ctx (assoc before-ctx
                             :results (:results exec-result)
                             :errors (into (:errors before-ctx) (:errors exec-result)))

            ;; Step 4: Run after-dispatch interceptors
            after-ctx (interceptors/run-after interceptors :dispatch final-ctx)]

        {:results (:results after-ctx)
         :errors (:errors after-ctx)}))))
