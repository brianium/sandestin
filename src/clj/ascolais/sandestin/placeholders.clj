(ns ascolais.sandestin.placeholders
  "Placeholder interpolation for Sandestin dispatch.

   Placeholders are vectors like [::some/placeholder & args] that get
   replaced with actual values from dispatch-data at dispatch time.

   Placeholders can be nested - a placeholder might resolve to another
   placeholder which then gets resolved.")

;; Forward declaration for mutual recursion
(declare interpolate)

(defn placeholder?
  "Returns true if x is a placeholder vector.

   A placeholder is a vector starting with a qualified keyword that
   is registered in the placeholders map."
  [placeholders-map x]
  (and (vector? x)
       (qualified-keyword? (first x))
       (contains? placeholders-map (first x))))

(defn resolve-placeholder
  "Resolve a single placeholder to its value.

   First interpolates any placeholder arguments, then calls the handler.
   Returns the resolved value, which may itself be a placeholder
   (requiring further resolution)."
  [placeholders-map dispatch-data placeholder max-depth]
  (let [[placeholder-key & args] placeholder
        registration (get placeholders-map placeholder-key)]
    (if-not registration
      ;; Unknown placeholder - return as-is (will cause error later or be handled)
      placeholder
      (let [handler (:ascolais.sandestin/handler registration)
            ;; Interpolate arguments first (handles nested placeholders)
            interpolated-args (mapv #(interpolate placeholders-map dispatch-data % (dec max-depth)) args)]
        (apply handler dispatch-data interpolated-args)))))

(defn interpolate
  "Recursively interpolate placeholders in a data structure.

   Walks the data structure and replaces any placeholder vectors
   with their resolved values. Continues until no more placeholders
   remain (handles nested placeholders).

   Arguments:
   - placeholders-map: Map of placeholder-key -> registration
   - dispatch-data: Data available to placeholder handlers
   - x: The data structure to interpolate
   - max-depth: Maximum interpolation depth (prevents infinite loops)

   Returns the interpolated data structure."
  ([placeholders-map dispatch-data x]
   (interpolate placeholders-map dispatch-data x 10))
  ([placeholders-map dispatch-data x max-depth]
   (if (zero? max-depth)
     x ;; Stop at max depth to prevent infinite loops
     (cond
       ;; Placeholder vector - resolve it
       (placeholder? placeholders-map x)
       (let [resolved (resolve-placeholder placeholders-map dispatch-data x max-depth)]
         ;; If resolved to same value, stop (prevents infinite loop)
         (if (= resolved x)
           x
           ;; Otherwise, continue interpolating the result
           (interpolate placeholders-map dispatch-data resolved (dec max-depth))))

       ;; Regular vector - interpolate each element
       (vector? x)
       (mapv #(interpolate placeholders-map dispatch-data % max-depth) x)

       ;; Map - interpolate values
       (map? x)
       (reduce-kv
        (fn [m k v]
          (assoc m k (interpolate placeholders-map dispatch-data v max-depth)))
        {}
        x)

       ;; Sequence - interpolate each element (preserving type where possible)
       (seq? x)
       (map #(interpolate placeholders-map dispatch-data % max-depth) x)

       ;; Anything else - return as-is
       :else x))))

(defn interpolate-effects
  "Interpolate placeholders in a vector of effects.

   Convenience function that interpolates an effects vector."
  [placeholders-map dispatch-data effects]
  (interpolate placeholders-map dispatch-data effects))
