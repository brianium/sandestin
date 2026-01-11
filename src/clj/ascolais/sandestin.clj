(ns ascolais.sandestin
  "Effect dispatch library with schema-driven discoverability.

   Core API:
   - create-dispatch: Create a dispatch function from registries
   - describe: Describe registered effects (Phase 4)
   - sample: Generate sample effects (Phase 4)
   - grep: Search effects (Phase 4)"
  (:require [ascolais.sandestin.registry :as registry]
            [ascolais.sandestin.dispatch :as dispatch]))

;; =============================================================================
;; Schema Constants
;; =============================================================================

(def EffectVector
  "Schema for an effect vector that will be dispatched.
   Use in schemas where an effect accepts continuation effects."
  [:vector [:cat :qualified-keyword [:* :any]]])

(def EffectsVector
  "Schema for a vector of effect vectors (what dispatch receives)."
  [:vector EffectVector])

;; =============================================================================
;; Dispatch Record
;; =============================================================================

(defrecord Dispatch [registry]
  clojure.lang.IFn
  (invoke [this effects]
    (dispatch/dispatch registry {} {} effects))
  (invoke [this system effects]
    (dispatch/dispatch registry system {} effects))
  (invoke [this system dispatch-data effects]
    (dispatch/dispatch registry system dispatch-data effects)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn create-dispatch
  "Create a dispatch function from a sequence of registries.

   Registries can be:
   - A vector [registry-fn & args] - calls (apply registry-fn args)
   - A zero-arity function - calls (registry-fn)
   - A plain map conforming to registry schema

   Returns a Dispatch record that is:
   - Callable as a function: (dispatch system dispatch-data effects)
   - Inspectable via describe, sample, grep functions

   Example:
     (def dispatch
       (create-dispatch
         [[db/registry {:dbtype \"sqlite\"}]
          logger/registry]))

     (dispatch system {} [[::db/execute sql-vec opts result-fx]])"
  [registries]
  (let [merged (registry/merge-registries registries)]
    (->Dispatch merged)))

;; =============================================================================
;; Discoverability (Phase 4 - stubs for now)
;; =============================================================================

(defn describe
  "Describe registered items.

   (describe dispatch)              ;; all items
   (describe dispatch ::some/key)   ;; specific item by key
   (describe dispatch :effects)     ;; all effects

   Returns a map or sequence of maps with metadata about registered items."
  ([dispatch]
   (describe dispatch :all))
  ([dispatch key-or-type]
   ;; TODO: Implement in Phase 4
   (throw (ex-info "describe not yet implemented (Phase 4)" {}))))

(defn sample
  "Generate sample effect vectors using Malli's generation.

   (sample dispatch ::db/execute)     ;; one sample
   (sample dispatch ::db/execute 5)   ;; five samples

   Returns vectors that conform to the registered schema."
  ([dispatch key]
   (sample dispatch key 1))
  ([dispatch key n]
   ;; TODO: Implement in Phase 4
   (throw (ex-info "sample not yet implemented (Phase 4)" {}))))

(defn grep
  "Search registered items by pattern.

   (grep dispatch \"database\")       ;; search descriptions
   (grep dispatch #\"execute.*\")     ;; regex on keys and descriptions

   Returns sequence of matching item descriptions."
  [dispatch pattern]
  ;; TODO: Implement in Phase 4
  (throw (ex-info "grep not yet implemented (Phase 4)" {})))

(defn schemas
  "Return a map of all schemas.

   (schemas dispatch)  ;; => {::db/execute [:tuple ...], ...}

   Useful for building composite schemas."
  [dispatch]
  ;; TODO: Implement in Phase 4
  (throw (ex-info "schemas not yet implemented (Phase 4)" {})))
