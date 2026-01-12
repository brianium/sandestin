(ns ascolais.sandestin.actions
  "Action expansion for Sandestin dispatch.

   Actions are pure functions that transform state into effect vectors.
   They are expanded recursively until only effects remain.")

(defn action?
  "Returns true if the effect-key is registered as an action."
  [registry effect-key]
  (contains? (:ascolais.sandestin/actions registry) effect-key))

(defn effect?
  "Returns true if the effect-key is registered as an effect."
  [registry effect-key]
  (contains? (:ascolais.sandestin/effects registry) effect-key))

(defn get-state
  "Extract immutable state from system using registry's system->state fn.

   If no system->state is registered, returns nil."
  [registry system]
  (if-let [system->state (:ascolais.sandestin/system->state registry)]
    (system->state system)
    nil))

(defn expand-action
  "Expand a single action into effects.

   Returns {:expanded [...effects...]} on success
   or {:error {...}} on failure."
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

(defn expand-actions
  "Recursively expand actions until only effects remain.

   Takes a vector of actions/effects and returns:
   {:effects [...] :errors [...]}

   Actions are expanded recursively. Effects pass through unchanged.
   Unknown keys (neither action nor effect) are treated as errors."
  [registry state actions-or-effects max-depth]
  (if (zero? max-depth)
    {:effects []
     :errors [{:phase :expand-action
               :err (ex-info "Max action expansion depth reached" {})}]}
    (reduce
     (fn [{:keys [effects errors]} item]
       (let [[item-key] item]
         (cond
           ;; It's an effect - pass through
           (effect? registry item-key)
           {:effects (conj effects item)
            :errors errors}

           ;; It's an action - expand it
           (action? registry item-key)
           (let [{:keys [expanded error]} (expand-action registry state item)]
             (if error
               {:effects effects
                :errors (conj errors error)}
               ;; Recursively expand the result
               (let [recursive-result (expand-actions registry state expanded (dec max-depth))]
                 {:effects (into effects (:effects recursive-result))
                  :errors (into errors (:errors recursive-result))})))

           ;; Unknown - error
           :else
           {:effects effects
            :errors (conj errors {:phase :expand-action
                                  :action item
                                  :err (ex-info "Unknown action or effect"
                                                {:key item-key})})})))
     {:effects [] :errors []}
     actions-or-effects)))
