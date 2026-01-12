(ns ascolais.sandestin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sandestin :as s]
            [ascolais.sandestin.registry :as registry]
            [ascolais.sandestin.placeholders :as placeholders]))

;; =============================================================================
;; Test Fixtures - Example Registries
;; =============================================================================

(def simple-registry
  "A simple registry with one effect."
  {::s/effects
   {::log
    {::s/description "Log a message"
     ::s/schema [:tuple [:= ::log] :string]
     ::s/handler (fn [_ctx _system message]
                   {:logged message})}}})

(defn counter-registry
  "A registry with dependencies (counter atom)."
  [counter-atom]
  {::s/effects
   {::increment
    {::s/description "Increment the counter"
     ::s/schema [:tuple [:= ::increment] :int]
     ::s/handler (fn [_ctx _system n]
                   (swap! counter-atom + n))}}})

(def async-registry
  "A registry demonstrating async dispatch."
  {::s/effects
   {::fetch-and-process
    {::s/description "Fetch data and dispatch result effect"
     ::s/schema [:tuple [:= ::fetch-and-process] :string :vector]
     ::s/handler (fn [{:keys [dispatch]} _system url result-fx]
                   ;; Simulate async: immediately dispatch continuation
                   (let [fake-result {:data (str "fetched:" url)}]
                     (dispatch {::fetch-result fake-result} result-fx))
                   :initiated)}

    ::use-result
    {::s/description "Use the fetched result"
     ::s/schema [:tuple [:= ::use-result]]
     ::s/handler (fn [{:keys [dispatch-data]} _system]
                   (::fetch-result dispatch-data))}}})

(def error-registry
  "A registry with effects that throw."
  {::s/effects
   {::explode
    {::s/description "An effect that always throws"
     ::s/schema [:tuple [:= ::explode]]
     ::s/handler (fn [_ctx _system]
                   (throw (ex-info "Boom!" {:reason :test})))}}})

;; =============================================================================
;; Registry Resolution Tests
;; =============================================================================

(deftest resolve-registry-test
  (testing "resolves plain map"
    (is (= simple-registry
           (registry/resolve-registry simple-registry))))

  (testing "resolves zero-arity function"
    (let [registry-fn (fn [] simple-registry)]
      (is (= simple-registry
             (registry/resolve-registry registry-fn)))))

  (testing "resolves vector with function and args"
    (let [counter (atom 0)
          resolved (registry/resolve-registry [counter-registry counter])]
      ;; Can't compare functions directly, so check structure
      (is (contains? (::s/effects resolved) ::increment))
      (is (fn? (get-in resolved [::s/effects ::increment ::s/handler])))))

  (testing "throws on invalid spec"
    (is (thrown? Exception (registry/resolve-registry "invalid")))))

;; =============================================================================
;; Registry Merging Tests
;; =============================================================================

(deftest merge-registries-test
  (testing "merges multiple registries"
    (let [counter (atom 0)
          merged (registry/merge-registries
                  [simple-registry
                   [counter-registry counter]])]
      (is (contains? (::s/effects merged) ::log))
      (is (contains? (::s/effects merged) ::increment))))

  (testing "later registry wins on conflict"
    (let [registry-a {::s/effects {::foo {::s/handler (fn [_ _] :a)}}}
          registry-b {::s/effects {::foo {::s/handler (fn [_ _] :b)}}}
          merged (registry/merge-registries [registry-a registry-b])
          handler (get-in merged [::s/effects ::foo ::s/handler])]
      (is (= :b (handler nil nil)))))

  (testing "interceptors are concatenated"
    (let [interceptor-a {:id ::a}
          interceptor-b {:id ::b}
          registry-a {::s/interceptors [interceptor-a]}
          registry-b {::s/interceptors [interceptor-b]}
          merged (registry/merge-registries [registry-a registry-b])]
      (is (= [interceptor-a interceptor-b]
             (::s/interceptors merged))))))

;; =============================================================================
;; Dispatch Creation Tests
;; =============================================================================

(deftest create-dispatch-test
  (testing "creates callable dispatch"
    (let [dispatch (s/create-dispatch [simple-registry])]
      (is (instance? ascolais.sandestin.Dispatch dispatch))
      (is (ifn? dispatch))))

  (testing "dispatch can be called with effects"
    (let [dispatch (s/create-dispatch [simple-registry])
          result (dispatch {} {} [[::log "hello"]])]
      (is (map? result))
      (is (contains? result :results))
      (is (contains? result :errors)))))

;; =============================================================================
;; Effect Execution Tests
;; =============================================================================

(deftest basic-effect-dispatch-test
  (testing "executes single effect"
    (let [dispatch (s/create-dispatch [simple-registry])
          {:keys [results errors]} (dispatch {} {} [[::log "test message"]])]
      (is (empty? errors))
      (is (= 1 (count results)))
      (is (= {:logged "test message"} (:res (first results))))))

  (testing "executes multiple effects in order"
    (let [log (atom [])
          registry {::s/effects
                    {::append
                     {::s/handler (fn [_ctx _system msg]
                                    (swap! log conj msg))}}}
          dispatch (s/create-dispatch [registry])
          {:keys [errors]} (dispatch {} {} [[::append "first"]
                                            [::append "second"]
                                            [::append "third"]])]
      (is (empty? errors))
      (is (= ["first" "second" "third"] @log))))

  (testing "effect receives system"
    (let [received-system (atom nil)
          registry {::s/effects
                    {::capture-system
                     {::s/handler (fn [_ctx system]
                                    (reset! received-system system))}}}
          dispatch (s/create-dispatch [registry])
          test-system {:db :connection :config {:port 3000}}]
      (dispatch test-system {} [[::capture-system]])
      (is (= test-system @received-system))))

  (testing "effect receives dispatch-data"
    (let [received-data (atom nil)
          registry {::s/effects
                    {::capture-dispatch-data
                     {::s/handler (fn [{:keys [dispatch-data]} _system]
                                    (reset! received-data dispatch-data))}}}
          dispatch (s/create-dispatch [registry])
          test-data {:request {:uri "/test"} :user-id 42}]
      (dispatch {} test-data [[::capture-dispatch-data]])
      (is (= test-data @received-data)))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest error-handling-test
  (testing "collects errors without stopping"
    (let [log (atom [])
          registry {::s/effects
                    {::ok {::s/handler (fn [_ _ msg] (swap! log conj msg))}
                     ::fail {::s/handler (fn [_ _] (throw (ex-info "Failed" {})))}}}
          dispatch (s/create-dispatch [registry])
          {:keys [results errors]} (dispatch {} {} [[::ok "before"]
                                                    [::fail]
                                                    [::ok "after"]])]
      ;; Both ::ok effects should run
      (is (= ["before" "after"] @log))
      ;; One error collected
      (is (= 1 (count errors)))
      (is (= :execute-effect (:phase (first errors))))
      ;; Two successful results
      (is (= 2 (count results)))))

  (testing "unknown effect produces error"
    (let [dispatch (s/create-dispatch [simple-registry])
          {:keys [errors]} (dispatch {} {} [[::unknown-effect "arg"]])]
      (is (= 1 (count errors)))
      ;; Error now comes from action expansion phase
      (is (= :expand-action (:phase (first errors))))
      (is (= ::unknown-effect
             (first (:action (first errors))))))))

;; =============================================================================
;; Async Continuation Tests
;; =============================================================================

(deftest async-dispatch-test
  (testing "effect can dispatch continuation effects"
    (let [dispatch (s/create-dispatch [async-registry])
          {:keys [results errors]} (dispatch {} {}
                                             [[::fetch-and-process
                                               "http://example.com"
                                               [[::use-result]]]])]
      (is (empty? errors))
      ;; First effect returns :initiated
      (is (= :initiated (:res (first results))))
      ;; The continuation was dispatched synchronously in this test
      ))

  (testing "continuation dispatch merges dispatch-data"
    (let [captured (atom nil)
          registry {::s/effects
                    {::parent
                     {::s/handler (fn [{:keys [dispatch]} _system child-fx]
                                    (dispatch {:parent-data :from-parent} child-fx)
                                    :parent-done)}
                     ::child
                     {::s/handler (fn [{:keys [dispatch-data]} _system]
                                    (reset! captured dispatch-data))}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {} {:original :data} [[::parent [[::child]]]])
      (is (= {:original :data :parent-data :from-parent}
             @captured)))))

;; =============================================================================
;; Dispatch Arities Test
;; =============================================================================

(deftest dispatch-arities-test
  (testing "dispatch with 1 arg (effects only)"
    (let [dispatch (s/create-dispatch [simple-registry])
          {:keys [results]} (dispatch [[::log "one-arg"]])]
      (is (= 1 (count results)))))

  (testing "dispatch with 2 args (system + effects)"
    (let [dispatch (s/create-dispatch [simple-registry])
          {:keys [results]} (dispatch {:system :data} [[::log "two-args"]])]
      (is (= 1 (count results)))))

  (testing "dispatch with 3 args (system + dispatch-data + effects)"
    (let [dispatch (s/create-dispatch [simple-registry])
          {:keys [results]} (dispatch {:sys :tem} {:dd :ata} [[::log "three-args"]])]
      (is (= 1 (count results))))))

;; =============================================================================
;; Phase 2: Placeholder Tests
;; =============================================================================

(def placeholder-registry
  "Registry with placeholders for testing."
  {::s/effects
   {::greet
    {::s/description "Greet someone"
     ::s/handler (fn [_ctx _system name]
                   (str "Hello, " name "!"))}}

   ::s/placeholders
   {::user-name
    {::s/description "Get user name from dispatch-data"
     ::s/handler (fn [dispatch-data]
                   (:user-name dispatch-data))}

    ::upper
    {::s/description "Uppercase a value"
     ::s/handler (fn [dispatch-data value]
                   (clojure.string/upper-case value))}

    ::get-in-data
    {::s/description "Get nested value from dispatch-data"
     ::s/handler (fn [dispatch-data & path]
                   (get-in dispatch-data (vec path)))}}})

(deftest placeholder-interpolation-test
  (testing "basic placeholder resolution"
    (let [placeholders-map (::s/placeholders placeholder-registry)
          dispatch-data {:user-name "Alice"}
          result (placeholders/interpolate
                  placeholders-map dispatch-data
                  [::user-name])]
      (is (= "Alice" result))))

  (testing "placeholder in effect vector"
    (let [dispatch (s/create-dispatch [placeholder-registry])
          {:keys [results errors]} (dispatch {} {:user-name "Bob"}
                                             [[::greet [::user-name]]])]
      (is (empty? errors))
      (is (= "Hello, Bob!" (:res (first results))))))

  (testing "placeholder with arguments"
    (let [placeholders-map (::s/placeholders placeholder-registry)
          result (placeholders/interpolate
                  placeholders-map {:user-name "alice"}
                  [::upper "hello"])]
      (is (= "HELLO" result))))

  (testing "nested placeholder resolution"
    (let [placeholders-map (::s/placeholders placeholder-registry)
          dispatch-data {:user-name "alice"}
          ;; [::upper [::user-name]] should resolve to "ALICE"
          result (placeholders/interpolate
                  placeholders-map dispatch-data
                  [::upper [::user-name]])]
      (is (= "ALICE" result))))

  (testing "placeholder in nested data structure"
    (let [placeholders-map (::s/placeholders placeholder-registry)
          dispatch-data {:request {:user {:name "Charlie"}}}
          result (placeholders/interpolate
                  placeholders-map dispatch-data
                  {:greeting [::get-in-data :request :user :name]})]
      (is (= {:greeting "Charlie"} result))))

  (testing "unknown placeholder passes through"
    (let [placeholders-map (::s/placeholders placeholder-registry)
          result (placeholders/interpolate
                  placeholders-map {}
                  [::unknown-placeholder "arg"])]
      ;; Unknown placeholders are left as-is
      (is (= [::unknown-placeholder "arg"] result)))))

;; =============================================================================
;; Phase 2: Action Tests
;; =============================================================================

(def action-registry
  "Registry with actions for testing."
  {::s/effects
   {::log
    {::s/description "Log a message"
     ::s/handler (fn [_ctx _system msg]
                   {:logged msg})}

    ::save
    {::s/description "Save data"
     ::s/handler (fn [_ctx _system key value]
                   {:saved {key value}})}}

   ::s/actions
   {::log-and-save
    {::s/description "Log and save in one action"
     ::s/handler (fn [_state msg key value]
                   [[::log msg]
                    [::save key value]])}

    ::increment-and-log
    {::s/description "Increment counter in state and log"
     ::s/handler (fn [state n]
                   (let [new-val (+ (:counter state 0) n)]
                     [[::log (str "Counter is now: " new-val)]
                      [::save :counter new-val]]))}

    ::nested-action
    {::s/description "Action that returns another action"
     ::s/handler (fn [_state msg]
                   [[::log-and-save msg :from "nested"]])}}

   ::s/system->state
   (fn [system]
     (:state system))})

(deftest action-expansion-test
  (testing "action expands to effects"
    (let [dispatch (s/create-dispatch [action-registry])
          {:keys [results errors]} (dispatch {} {}
                                             [[::log-and-save "hello" :key "value"]])]
      (is (empty? errors))
      (is (= 2 (count results)))
      (is (= {:logged "hello"} (:res (first results))))
      (is (= {:saved {:key "value"}} (:res (second results))))))

  (testing "action receives state from system->state"
    (let [dispatch (s/create-dispatch [action-registry])
          system {:state {:counter 10}}
          {:keys [results errors]} (dispatch system {}
                                             [[::increment-and-log 5]])]
      (is (empty? errors))
      (is (= 2 (count results)))
      (is (= {:logged "Counter is now: 15"} (:res (first results))))))

  (testing "nested actions expand recursively"
    (let [dispatch (s/create-dispatch [action-registry])
          {:keys [results errors]} (dispatch {} {}
                                             [[::nested-action "from-outer"]])]
      (is (empty? errors))
      ;; nested-action -> log-and-save -> [::log ::save]
      (is (= 2 (count results)))
      (is (= {:logged "from-outer"} (:res (first results))))))

  (testing "mixed actions and effects"
    (let [dispatch (s/create-dispatch [action-registry])
          {:keys [results errors]} (dispatch {} {}
                                             [[::log "direct"]
                                              [::log-and-save "from-action" :k "v"]
                                              [::log "after"]])]
      (is (empty? errors))
      (is (= 4 (count results)))
      (is (= [{:logged "direct"}
              {:logged "from-action"}
              {:saved {:k "v"}}
              {:logged "after"}]
             (mapv :res results)))))

  (testing "unknown action produces error"
    (let [dispatch (s/create-dispatch [action-registry])
          {:keys [errors]} (dispatch {} {} [[::unknown-action "arg"]])]
      (is (= 1 (count errors)))
      (is (= :expand-action (:phase (first errors)))))))

;; =============================================================================
;; Phase 2: Combined Placeholders and Actions Tests
;; =============================================================================

(def combined-registry
  "Registry combining placeholders and actions."
  {::s/effects
   {::greet
    {::s/handler (fn [_ctx _system name]
                   (str "Hello, " name "!"))}}

   ::s/actions
   {::greet-user
    {::s/handler (fn [state]
                   [[::greet (:current-user state)]])}}

   ::s/placeholders
   {::user
    {::s/handler (fn [dd] (:user dd))}}

   ::s/system->state
   (fn [system] (:state system))})

(deftest combined-placeholders-actions-test
  (testing "placeholder resolved before action expansion"
    (let [registry {::s/effects
                    {::log {::s/handler (fn [_ _ msg] {:logged msg})}}
                    ::s/actions
                    {::log-user {::s/handler (fn [_state user]
                                               [[::log user]])}}
                    ::s/placeholders
                    {::user {::s/handler (fn [dd] (:user dd))}}}
          dispatch (s/create-dispatch [registry])
          {:keys [results errors]} (dispatch {} {:user "PlaceholderUser"}
                                             [[::log-user [::user]]])]
      (is (empty? errors))
      (is (= {:logged "PlaceholderUser"} (:res (first results))))))

  (testing "action uses state, effect uses placeholder result"
    (let [dispatch (s/create-dispatch [combined-registry])
          system {:state {:current-user "StateUser"}}
          {:keys [results errors]} (dispatch system {}
                                             [[::greet-user]])]
      (is (empty? errors))
      (is (= "Hello, StateUser!" (:res (first results)))))))
