(ns ascolais.sandestin.registry
  "Registry resolution and merging for Sandestin dispatch.")

(defn resolve-registry
  "Resolve a registry spec to a registry map.

   Accepts:
   - A map (returned as-is)
   - A zero-arity function (called to produce map)
   - A vector [fn & args] (applies fn to args)"
  [spec]
  (cond
    (map? spec) spec
    (fn? spec) (spec)
    (vector? spec) (apply (first spec) (rest spec))
    :else (throw (ex-info "Invalid registry spec" {:spec spec}))))

(def ^:private merge-strategies
  "Merge strategies for registry keys.

   - :merge - shallow merge maps (later wins for same key)
   - :into - concatenate sequences
   - :replace - last value wins"
  {:ascolais.sandestin/effects :merge
   :ascolais.sandestin/actions :merge
   :ascolais.sandestin/placeholders :merge
   :ascolais.sandestin/interceptors :into
   :ascolais.sandestin/system-schema :merge
   :ascolais.sandestin/system->state :replace})

(defn- merge-key
  "Merge a single key according to its strategy."
  [strategy existing new-val]
  (case strategy
    :merge (merge existing new-val)
    :into (into (or existing []) new-val)
    :replace new-val
    ;; Default: treat unknown keys as user metadata, merge them
    (if (map? new-val)
      (merge existing new-val)
      new-val)))

(defn- detect-conflicts
  "Detect and log conflicts when merging registries.
   Returns a sequence of conflict descriptions."
  [existing new-registry]
  (let [effect-conflicts (filter #(contains? (:ascolais.sandestin/effects existing) %)
                                 (keys (:ascolais.sandestin/effects new-registry)))
        action-conflicts (filter #(contains? (:ascolais.sandestin/actions existing) %)
                                 (keys (:ascolais.sandestin/actions new-registry)))
        placeholder-conflicts (filter #(contains? (:ascolais.sandestin/placeholders existing) %)
                                      (keys (:ascolais.sandestin/placeholders new-registry)))]
    (concat
     (map #(hash-map :type :effect :key %) effect-conflicts)
     (map #(hash-map :type :action :key %) action-conflicts)
     (map #(hash-map :type :placeholder :key %) placeholder-conflicts))))

(defn merge-registries
  "Merge a sequence of registry specs into a single registry map.

   Registry specs are resolved and merged left-to-right.
   Conflicts are logged via tap> but later registrations win."
  [registry-specs]
  (reduce
   (fn [merged spec]
     (let [registry (resolve-registry spec)
           conflicts (detect-conflicts merged registry)]
       ;; Log conflicts via tap>
       (when (seq conflicts)
         (tap> {:sandestin/event :registry-conflict
                :conflicts conflicts}))
       ;; Merge each key according to its strategy
       (reduce-kv
        (fn [m k v]
          (let [strategy (get merge-strategies k :replace)]
            (update m k #(merge-key strategy % v))))
        merged
        registry)))
   {}
   registry-specs))
