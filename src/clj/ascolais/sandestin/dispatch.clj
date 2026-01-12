(ns ascolais.sandestin.dispatch
  "Core dispatch implementation for Sandestin.

   Dispatch flow:
   1. Interpolate placeholders in input
   2. Expand actions to effects
   3. Execute effects"
  (:require [ascolais.sandestin.placeholders :as placeholders]
            [ascolais.sandestin.actions :as actions]))

(def ^:private max-action-depth
  "Maximum depth for action expansion to prevent infinite loops."
  100)

;; Forward declaration for mutual recursion
(declare dispatch)

(defn- execute-effect
  "Execute a single effect and return a result map.

   Returns {:effect [...] :res result} on success
   or {:effect [...] :err exception} on error."
  [registry ctx system effect]
  (let [[effect-key & args] effect
        effects-map (:ascolais.sandestin/effects registry)
        registration (get effects-map effect-key)]
    (if-not registration
      {:effect effect
       :err (ex-info "Unknown effect" {:effect-key effect-key
                                       :available-effects (keys effects-map)})}
      (try
        (let [handler (:ascolais.sandestin/handler registration)
              result (apply handler ctx system args)]
          {:effect effect
           :res result})
        (catch Exception e
          {:effect effect
           :err e})))))

(defn- execute-effects
  "Execute a sequence of effects and collect results.

   Returns {:results [...] :errors [...]}."
  [registry system dispatch-data effects]
  (let [;; Create dispatch function for async continuation
        ;; This goes through the full dispatch flow (interpolate -> expand -> execute)
        dispatch-fn (fn dispatch-continuation
                      ([fx]
                       (dispatch-continuation {} fx))
                      ([extra-dispatch-data fx]
                       (dispatch registry system
                                 (merge dispatch-data extra-dispatch-data)
                                 fx)))
        ;; Build context for effect handlers
        ctx {:dispatch dispatch-fn
             :dispatch-data dispatch-data
             :system system}]
    ;; Execute each effect
    (reduce
     (fn [{:keys [results errors]} effect]
       (let [result (execute-effect registry ctx system effect)]
         (if (:err result)
           {:results results
            :errors (conj errors {:phase :execute-effect
                                  :effect (:effect result)
                                  :err (:err result)})}
           {:results (conj results result)
            :errors errors})))
     {:results [] :errors []}
     effects)))

(defn dispatch
  "Dispatch actions/effects using the given registry.

   Flow:
   1. Interpolate placeholders in the input
   2. Expand actions to effects (recursively)
   3. Execute effects

   Arguments:
   - registry: A merged registry map
   - system: The live system (passed to effect handlers)
   - dispatch-data: Data available to handlers and placeholders
   - actions-or-effects: A vector of action/effect vectors

   Returns {:results [...] :errors [...]}"
  [registry system dispatch-data actions-or-effects]
  (let [;; Get placeholders map for interpolation
        placeholders-map (:ascolais.sandestin/placeholders registry)

        ;; Step 1: Interpolate placeholders
        interpolated (if (seq placeholders-map)
                       (placeholders/interpolate-effects
                        placeholders-map dispatch-data actions-or-effects)
                       actions-or-effects)

        ;; Step 2: Expand actions to effects
        state (actions/get-state registry system)
        {:keys [effects errors]} (actions/expand-actions
                                  registry state interpolated max-action-depth)]

    ;; Step 3: Execute effects (accumulating any expansion errors)
    (let [exec-result (execute-effects registry system dispatch-data effects)]
      {:results (:results exec-result)
       :errors (into errors (:errors exec-result))})))
