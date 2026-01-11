(ns ascolais.sandestin.dispatch
  "Core dispatch implementation for Sandestin.

   Phase 1: Effects only (no actions, placeholders, or interceptors).")

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
        dispatch-fn (fn dispatch
                      ([fx]
                       (dispatch {} fx))
                      ([extra-dispatch-data fx]
                       (execute-effects registry system
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
  "Dispatch effects using the given registry.

   Arguments:
   - registry: A merged registry map
   - system: The live system (passed to effect handlers)
   - dispatch-data: Data available to handlers and placeholders
   - effects: A vector of effect vectors

   Returns {:results [...] :errors [...]}"
  [registry system dispatch-data effects]
  (execute-effects registry system dispatch-data effects))
