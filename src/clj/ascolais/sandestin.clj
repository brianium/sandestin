(ns ascolais.sandestin
  "Effect dispatch library with schema-driven discoverability.

   Core API:
   - create-dispatch: Create a dispatch function from registries
   - describe: Describe registered effects, actions, placeholders
   - sample: Generate sample effects using Malli
   - grep: Search registered items by pattern
   - schemas: Get all schemas as a map"
  (:require [ascolais.sandestin.registry :as registry]
            [ascolais.sandestin.dispatch :as dispatch]
            [ascolais.sandestin.describe :as describe]))

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
;; Discoverability
;; =============================================================================

(defn describe
  "Describe registered items.

   (describe dispatch)              ;; all items
   (describe dispatch ::some/key)   ;; specific item by key
   (describe dispatch :effects)     ;; all effects
   (describe dispatch :actions)     ;; all actions
   (describe dispatch :placeholders) ;; all placeholders

   Returns a map (for single item) or sequence of maps with:
   - :ascolais.sandestin/key - the registered keyword
   - :ascolais.sandestin/type - :effect, :action, or :placeholder
   - :ascolais.sandestin/description - human-readable description
   - :ascolais.sandestin/schema - Malli schema
   - :ascolais.sandestin/system-keys - system dependencies (if declared)
   - Plus any user-defined metadata"
  ([dispatch]
   (describe/describe dispatch))
  ([dispatch key-or-type]
   (describe/describe dispatch key-or-type)))

(defn sample
  "Generate sample effect/action/placeholder vectors using Malli's generation.

   (sample dispatch ::db/execute)     ;; one sample
   (sample dispatch ::db/execute 5)   ;; five samples

   Returns a vector (or sequence of vectors) that conform to the schema.
   Returns nil if no schema is defined or generation fails."
  ([dispatch key]
   (describe/sample dispatch key))
  ([dispatch key n]
   (describe/sample dispatch key n)))

(defn grep
  "Search registered items by pattern.

   (grep dispatch \"database\")       ;; search descriptions and keys
   (grep dispatch #\"execute.*\")     ;; regex on keys and descriptions

   Returns sequence of matching item descriptions."
  [dispatch pattern]
  (describe/grep dispatch pattern))

(defn schemas
  "Return a map of all schemas.

   (schemas dispatch)  ;; => {::db/execute [:tuple ...], ...}

   Useful for building composite schemas."
  [dispatch]
  (describe/schemas dispatch))
