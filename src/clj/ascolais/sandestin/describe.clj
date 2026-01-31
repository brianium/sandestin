(ns ascolais.sandestin.describe
  "Discoverability functions for Sandestin dispatch.

   Provides functions to describe, sample, search, and inspect
   registered effects, actions, and placeholders."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]))

;; Alias for the public namespace to use for output keys
(def ^:private s-ns "ascolais.sandestin")
(defn- s-key [k] (keyword s-ns (name k)))

;; =============================================================================
;; Deep Text Extraction (for grep)
;; =============================================================================

(defn- walk-schema-descriptions
  "Extract all :description values from a Malli schema.
   Walks the schema recursively to find descriptions in:
   - Schema properties (top-level)
   - Map entry properties
   - Tuple/vector element properties
   - Union/intersection branch properties"
  [schema]
  (when schema
    (let [descriptions (atom [])]
      (letfn [(extract-props [props]
                (when-let [desc (:description props)]
                  (swap! descriptions conj desc)))
              (walk [s]
                (when s
                  (cond
                    ;; Malli schema as vector: [:type props? & children]
                    (vector? s)
                    (let [[type-kw & rest] s
                          [props & children] (if (map? (first rest))
                                               rest
                                               (cons nil rest))]
                      (extract-props props)
                      ;; For :map schemas, entries are [key props? schema]
                      (when (= :map type-kw)
                        (doseq [entry children]
                          (when (vector? entry)
                            (let [[_k & entry-rest] entry
                                  [entry-props entry-schema] (if (map? (first entry-rest))
                                                               entry-rest
                                                               (cons nil entry-rest))]
                              (extract-props entry-props)
                              (walk entry-schema)))))
                      ;; For other schemas, recursively walk children
                      (when-not (= :map type-kw)
                        (doseq [child children]
                          (walk child))))

                    ;; Handle refs and other forms
                    (keyword? s) nil
                    :else nil)))]
        (walk schema))
      (str/join " " @descriptions))))

(defn- stringify-value
  "Recursively stringify a value for text search."
  [v]
  (cond
    (string? v) v
    (keyword? v) (str v)
    (symbol? v) (str v)
    (number? v) (str v)
    (map? v) (str/join " " (mapcat (fn [[k v]] [(stringify-value k) (stringify-value v)]) v))
    (sequential? v) (str/join " " (map stringify-value v))
    (set? v) (str/join " " (map stringify-value v))
    :else (str v)))

(defn- registration->searchable-text
  "Extract all searchable text from a registration description map.
   Includes:
   - The effect/action/placeholder key
   - The ::s/description
   - All :description values from the ::s/schema (Malli properties)
   - All non-core keys recursively stringified (library metadata)"
  [item]
  (let [core-keys #{(s-key :key) (s-key :type) (s-key :description)
                    (s-key :schema) (s-key :handler) (s-key :system-keys)}
        key-text (str (get item (s-key :key)))
        desc-text (str (get item (s-key :description)))
        schema-text (walk-schema-descriptions (get item (s-key :schema)))
        ;; All other keys (library metadata like ::phandaal/returns, ::foo/examples)
        other-keys (remove #(core-keys %) (keys item))
        other-text (str/join " " (map #(stringify-value (get item %)) other-keys))]
    (str/join " " (remove str/blank? [key-text desc-text schema-text other-text]))))

(defn- registration->description
  "Convert a registration entry to a description map."
  [reg-type k registration]
  (let [base {(s-key :key) k
              (s-key :type) reg-type
              (s-key :description) (or (:ascolais.sandestin/description registration) "")
              (s-key :schema) (:ascolais.sandestin/schema registration)}]
    ;; Include system-keys if present
    (cond-> base
      (:ascolais.sandestin/system-keys registration)
      (assoc (s-key :system-keys) (:ascolais.sandestin/system-keys registration))

      ;; Include any user metadata (non-sandestin namespaced keys)
      true
      (merge (into {}
                   (filter (fn [[k _]]
                             (and (keyword? k)
                                  (not= "ascolais.sandestin" (namespace k))))
                           registration))))))

(defn- collect-all
  "Collect all registrations from the registry as descriptions."
  [registry]
  (concat
   ;; Effects
   (for [[k reg] (:ascolais.sandestin/effects registry)]
     (registration->description :effect k reg))
   ;; Actions
   (for [[k reg] (:ascolais.sandestin/actions registry)]
     (registration->description :action k reg))
   ;; Placeholders
   (for [[k reg] (:ascolais.sandestin/placeholders registry)]
     (registration->description :placeholder k reg))))

(defn describe
  "Describe registered items in a dispatch.

   Arities:
   - (describe dispatch) - all items
   - (describe dispatch key) - specific item by qualified keyword
   - (describe dispatch :effects) - all effects
   - (describe dispatch :actions) - all actions
   - (describe dispatch :placeholders) - all placeholders

   Returns a map (for single item) or sequence of maps with:
   - :ascolais.sandestin/key - the registered keyword
   - :ascolais.sandestin/type - :effect, :action, or :placeholder
   - :ascolais.sandestin/description - human-readable description
   - :ascolais.sandestin/schema - Malli schema for the vector shape
   - :ascolais.sandestin/system-keys - keys this item expects in system (if declared)
   - Plus any user-defined metadata"
  ([dispatch]
   (describe dispatch :all))
  ([dispatch key-or-type]
   (let [registry (:registry dispatch)]
     (case key-or-type
       :all (collect-all registry)
       :effects (for [[k reg] (:ascolais.sandestin/effects registry)]
                  (registration->description :effect k reg))
       :actions (for [[k reg] (:ascolais.sandestin/actions registry)]
                  (registration->description :action k reg))
       :placeholders (for [[k reg] (:ascolais.sandestin/placeholders registry)]
                       (registration->description :placeholder k reg))
       ;; Assume it's a specific key
       (let [effects (:ascolais.sandestin/effects registry)
             actions (:ascolais.sandestin/actions registry)
             placeholders (:ascolais.sandestin/placeholders registry)]
         (cond
           (contains? effects key-or-type)
           (registration->description :effect key-or-type (get effects key-or-type))

           (contains? actions key-or-type)
           (registration->description :action key-or-type (get actions key-or-type))

           (contains? placeholders key-or-type)
           (registration->description :placeholder key-or-type (get placeholders key-or-type))

           :else nil))))))

(defn sample
  "Generate sample effect/action/placeholder vectors using Malli's generation.

   (sample dispatch ::db/execute)     ;; one sample
   (sample dispatch ::db/execute 5)   ;; five samples

   Returns a vector (or sequence of vectors) that conform to the schema.
   Returns nil if no schema is defined or generation fails."
  ([dispatch key]
   (sample dispatch key 1))
  ([dispatch key n]
   (let [desc (describe dispatch key)]
     (when-let [schema (get desc (s-key :schema))]
       (try
         (if (= n 1)
           (mg/generate schema)
           (mg/sample schema {:size n}))
         (catch Exception _
           nil))))))

(defn grep
  "Search registered items by pattern.

   Searches deeply across all discoverable metadata:
   - Effect/action/placeholder keys
   - Top-level descriptions
   - Malli schema :description properties (parameter docs)
   - All library-provided metadata (e.g., ::phandaal/returns, examples)

   (grep dispatch \"database\")       ;; search all metadata
   (grep dispatch #\"execute.*\")     ;; regex pattern
   (grep dispatch \"threshold\")      ;; finds effects with 'threshold' in param descriptions

   Returns sequence of matching item descriptions."
  [dispatch pattern]
  (let [all-items (describe dispatch :all)
        pattern-re (if (instance? java.util.regex.Pattern pattern)
                     pattern
                     (re-pattern (str "(?i)" (java.util.regex.Pattern/quote (str pattern)))))]
    (filter
     (fn [item]
       (re-find pattern-re (registration->searchable-text item)))
     all-items)))

(defn schemas
  "Return a map of all schemas.

   (schemas dispatch)  ;; => {::db/execute [:tuple ...], ...}

   Useful for building composite schemas."
  [dispatch]
  (let [registry (:registry dispatch)]
    (merge
     (into {} (for [[k reg] (:ascolais.sandestin/effects registry)
                    :when (:ascolais.sandestin/schema reg)]
                [k (:ascolais.sandestin/schema reg)]))
     (into {} (for [[k reg] (:ascolais.sandestin/actions registry)
                    :when (:ascolais.sandestin/schema reg)]
                [k (:ascolais.sandestin/schema reg)]))
     (into {} (for [[k reg] (:ascolais.sandestin/placeholders registry)
                    :when (:ascolais.sandestin/schema reg)]
                [k (:ascolais.sandestin/schema reg)])))))

(defn system-schema
  "Return the merged system schema.

   (system-schema dispatch)  ;; => {:db DataSourceSchema, :config ConfigSchema}

   Returns a map of system keys to their Malli schemas, merged from all
   registries. Use this to validate the system map or document requirements."
  [dispatch]
  (:ascolais.sandestin/system-schema (:registry dispatch)))
