(ns ascolais.sandestin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sandestin :as s]
            [ascolais.sandestin.registry :as registry]
            [ascolais.sandestin.placeholders :as placeholders]
            [ascolais.sandestin.interceptors :as interceptors]))

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
;; System Override Tests
;; =============================================================================

(deftest system-override-dispatch-test
  (testing "continuation dispatch with system override (3-arity)"
    (let [captured-system (atom nil)
          registry {::s/effects
                    {::parent
                     {::s/handler (fn [{:keys [dispatch system]} _system child-fx]
                                    ;; Override :sse key in system
                                    (dispatch {:sse :new-connection} {} child-fx)
                                    :parent-done)}
                     ::child
                     {::s/handler (fn [_ctx system]
                                    (reset! captured-system system)
                                    :child-done)}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {:sse :original-connection :request {:uri "/test"}}
                {}
                [[::parent [[::child]]]])
      ;; System should have :sse overridden but :request preserved
      (is (= :new-connection (:sse @captured-system)))
      (is (= {:uri "/test"} (:request @captured-system)))))

  (testing "system override merges with current system"
    (let [captured-system (atom nil)
          registry {::s/effects
                    {::router
                     {::s/handler (fn [{:keys [dispatch]} _system alt-db child-fx]
                                    ;; Override :db while preserving other system keys
                                    (dispatch {:db alt-db} {} child-fx)
                                    :routed)}
                     ::worker
                     {::s/handler (fn [_ctx system]
                                    (reset! captured-system system)
                                    :worked)}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {:db :primary-db :cache :redis :config {:env :prod}}
                {}
                [[::router :secondary-db [[::worker]]]])
      ;; :db should be overridden, other keys preserved
      (is (= :secondary-db (:db @captured-system)))
      (is (= :redis (:cache @captured-system)))
      (is (= {:env :prod} (:config @captured-system)))))

  (testing "system override with dispatch-data in same call"
    (let [captured (atom {})
          registry {::s/effects
                    {::parent
                     {::s/handler (fn [{:keys [dispatch]} _system child-fx]
                                    ;; Override both system and dispatch-data
                                    (dispatch {:sse :alt-connection}
                                              {:extra-data :from-parent}
                                              child-fx)
                                    :parent-done)}
                     ::child
                     {::s/handler (fn [{:keys [dispatch-data]} system]
                                    (reset! captured {:system system
                                                      :dispatch-data dispatch-data})
                                    :child-done)}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {:sse :original :db :postgres}
                {:request-id 123}
                [[::parent [[::child]]]])
      ;; Both system and dispatch-data should be merged
      (is (= :alt-connection (get-in @captured [:system :sse])))
      (is (= :postgres (get-in @captured [:system :db])))
      (is (= 123 (get-in @captured [:dispatch-data :request-id])))
      (is (= :from-parent (get-in @captured [:dispatch-data :extra-data])))))

  (testing "empty system override preserves original system"
    (let [captured-system (atom nil)
          registry {::s/effects
                    {::parent
                     {::s/handler (fn [{:keys [dispatch]} _system child-fx]
                                    ;; Empty override should preserve system
                                    (dispatch {} {} child-fx)
                                    :parent-done)}
                     ::child
                     {::s/handler (fn [_ctx system]
                                    (reset! captured-system system)
                                    :child-done)}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {:db :postgres :cache :redis} {} [[::parent [[::child]]]])
      (is (= {:db :postgres :cache :redis} @captured-system))))

  (testing "chained system overrides accumulate"
    (let [captured-system (atom nil)
          registry {::s/effects
                    {::level1
                     {::s/handler (fn [{:keys [dispatch]} _system child-fx]
                                    (dispatch {:added-by :level1} {} child-fx)
                                    :level1-done)}
                     ::level2
                     {::s/handler (fn [{:keys [dispatch]} _system child-fx]
                                    (dispatch {:added-by :level2 :also :here} {} child-fx)
                                    :level2-done)}
                     ::level3
                     {::s/handler (fn [_ctx system]
                                    (reset! captured-system system)
                                    :level3-done)}}}
          dispatch (s/create-dispatch [registry])]
      (dispatch {:original :data}
                {}
                [[::level1 [[::level2 [[::level3]]]]]])
      ;; Each level's override merges into the running system
      ;; level2 overrides level1's :added-by
      (is (= :level2 (:added-by @captured-system)))
      (is (= :here (:also @captured-system)))
      (is (= :data (:original @captured-system))))))

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

;; =============================================================================
;; Nested Continuation with Placeholder Tests
;; =============================================================================

(deftest nested-continuation-placeholder-test
  (testing "placeholder in depth-3 continuation accesses depth-2 dispatch-data"
    (let [captured (atom [])
          registry {::s/effects
                    {::level1
                     {::s/handler
                      (fn [{:keys [dispatch]} _system continuation]
                        (swap! captured conj {:level 1 :action "dispatching level2"})
                        (dispatch {:from-level1 "data-from-1"}
                                  continuation)
                        :level1-done)}

                     ::level2
                     {::s/handler
                      (fn [{:keys [dispatch dispatch-data]} _system continuation]
                        (swap! captured conj {:level 2
                                              :received (:from-level1 dispatch-data)})
                        (dispatch {:from-level2 "data-from-2"}
                                  continuation)
                        :level2-done)}

                     ::level3
                     {::s/handler
                      (fn [{:keys [dispatch-data]} _system]
                        (swap! captured conj {:level 3
                                              :from-level1 (:from-level1 dispatch-data)
                                              :from-level2 (:from-level2 dispatch-data)})
                        :level3-done)}}

                    ::s/placeholders
                    {::from-level2
                     {::s/handler (fn [dd] (:from-level2 dd))}}}

          dispatch (s/create-dispatch [registry])

          ;; Dispatch chain: level1 -> level2 -> level3 with placeholder
          {:keys [results errors]}
          (dispatch {} {:initial "data"}
                    [[::level1
                      [[::level2
                        [[::level3]]]]]])]

      (is (empty? errors))

      ;; Verify the captured data shows proper dispatch-data propagation
      (is (= 3 (count @captured)))
      (is (= "data-from-1" (:received (second @captured))))
      (is (= "data-from-1" (:from-level1 (nth @captured 2))))
      (is (= "data-from-2" (:from-level2 (nth @captured 2))))))

  (testing "placeholder resolves with dispatch-data from parent effect"
    (let [captured (atom nil)
          registry {::s/effects
                    {::fetch
                     {::s/handler
                      (fn [{:keys [dispatch]} _system result-fx]
                        ;; Simulate fetching data and dispatching continuation
                        (dispatch {:fetch-result {:id 42 :name "Alice"}}
                                  result-fx)
                        :fetch-started)}

                     ::process
                     {::s/handler
                      (fn [{:keys [dispatch]} _system data result-fx]
                        ;; Process the data and dispatch next continuation
                        (dispatch {:processed-data (str "Processed: " data)}
                                  result-fx)
                        :process-started)}

                     ::finalize
                     {::s/handler
                      (fn [_ctx _system final-data]
                        (reset! captured final-data)
                        :done)}}

                    ::s/placeholders
                    {::fetch-result
                     {::s/handler (fn [dd]
                                    ;; Self-preserve if data not available yet
                                    (or (:fetch-result dd) [::fetch-result]))}

                     ::fetch-name
                     {::s/handler (fn [dd]
                                    ;; Self-preserve if data not available yet
                                    (if-let [result (:fetch-result dd)]
                                      (:name result)
                                      [::fetch-name]))}

                     ::processed
                     {::s/handler (fn [dd]
                                    ;; Self-preserve if data not available yet
                                    (or (:processed-data dd) [::processed]))}}}

          dispatch (s/create-dispatch [registry])

          ;; Chain: fetch -> process (using fetch result) -> finalize (using processed)
          {:keys [errors]}
          (dispatch {} {}
                    [[::fetch
                      [[::process [::fetch-name]
                        [[::finalize [::processed]]]]]]])]

      (is (empty? errors))
      ;; The final effect should receive "Processed: Alice"
      (is (= "Processed: Alice" @captured)))))

;; =============================================================================
;; Placeholder Interpolation Timing Tests (Nexus 2025.10.1 behavior)
;; =============================================================================

(deftest placeholders-introduced-by-actions-test
  (testing "placeholders introduced by action expansion are interpolated"
    ;; This tests the "interpolate between action expansions" behavior
    ;; from Nexus 2025.10.1 - actions can return effects with placeholders
    ;; that reference values from the original dispatch-data
    (let [captured (atom nil)
          registry {::s/effects
                    {::send-email
                     {::s/handler
                      (fn [_ctx _system email-opts]
                        (reset! captured email-opts)
                        :sent)}}

                    ::s/actions
                    {::send-welcome
                     {::s/handler
                      (fn [_state]
                        ;; Action introduces a placeholder that should be
                        ;; resolved from the original dispatch-data
                        [[::send-email {:to [::user-email]
                                        :subject "Welcome!"}]])}}

                    ::s/placeholders
                    {::user-email
                     {::s/handler (fn [dd] (:user-email dd))}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {:user-email "alice@example.com"} [[::send-welcome]])

      (is (= {:to "alice@example.com" :subject "Welcome!"} @captured))))

  (testing "nested actions with placeholders at each level"
    (let [log (atom [])
          registry {::s/effects
                    {::log
                     {::s/handler
                      (fn [_ctx _system msg]
                        (swap! log conj msg)
                        :logged)}}

                    ::s/actions
                    {::outer-action
                     {::s/handler
                      (fn [_state]
                        ;; Returns another action with a placeholder
                        [[::inner-action [::greeting]]])}

                     ::inner-action
                     {::s/handler
                      (fn [_state greeting]
                        ;; Returns effect with value from placeholder
                        [[::log greeting]])}}

                    ::s/placeholders
                    {::greeting
                     {::s/handler (fn [dd] (:greeting dd))}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {:greeting "Hello, World!"} [[::outer-action]])

      (is (= ["Hello, World!"] @log)))))

(deftest self-preserving-placeholder-continuation-test
  (testing "self-preserving placeholders work with async continuations"
    ;; This documents the Nexus pattern for async effects:
    ;; 1. Placeholder returns itself when data isn't available yet
    ;; 2. Effect dispatches continuation with additional data
    ;; 3. Continuation dispatch resolves the placeholder
    (let [captured (atom nil)
          registry {::s/effects
                    {::async-fetch
                     {::s/handler
                      (fn [{:keys [dispatch]} _system url continuation-fx]
                        ;; Simulate async: dispatch continuation with result
                        (let [result {:data (str "fetched:" url)}]
                          (dispatch {::fetch-result result} continuation-fx))
                        :fetch-started)}

                     ::use-data
                     {::s/handler
                      (fn [_ctx _system data]
                        (reset! captured data)
                        :done)}}

                    ::s/placeholders
                    {::fetch-result
                     {::s/handler
                      (fn [dd]
                        ;; Self-preserving: return placeholder if data not available
                        (or (::fetch-result dd) [::fetch-result]))}}}

          dispatch (s/create-dispatch [registry])]

      ;; The [::fetch-result] placeholder in the continuation will be
      ;; preserved during initial dispatch, then resolved when the
      ;; effect dispatches with {::fetch-result result}
      (dispatch {} {}
                [[::async-fetch "http://example.com"
                  [[::use-data [::fetch-result]]]]])

      (is (= {:data "fetched:http://example.com"} @captured))))

  (testing "self-preserving placeholder with transformation"
    ;; More realistic example: extract a field from the async result
    ;; The key pattern is to check dispatch-data for availability
    (let [captured (atom nil)
          registry {::s/effects
                    {::db-query
                     {::s/handler
                      (fn [{:keys [dispatch]} _system _sql continuation-fx]
                        (let [rows [{:id 1 :name "Alice"}
                                    {:id 2 :name "Bob"}]]
                          (dispatch {::query-result rows} continuation-fx))
                        :query-started)}

                     ::process-names
                     {::s/handler
                      (fn [_ctx _system names]
                        (reset! captured names)
                        :processed)}}

                    ::s/placeholders
                    {::query-result
                     {::s/handler
                      (fn [dd]
                        (or (::query-result dd) [::query-result]))}

                     ::extract-names
                     {::s/handler
                      (fn [dd rows]
                        ;; Check dispatch-data to know if result is available
                        ;; This is more robust than checking the rows value
                        (if (::query-result dd)
                          (mapv :name rows)
                          ;; Self-preserve if query hasn't completed
                          [::extract-names rows]))}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {}
                [[::db-query "SELECT * FROM users"
                  [[::process-names [::extract-names [::query-result]]]]]])

      (is (= ["Alice" "Bob"] @captured)))))

;; =============================================================================
;; Phase 3: Interceptor Tests
;; =============================================================================

(deftest interceptor-dispatch-lifecycle-test
  (testing "before-dispatch and after-dispatch interceptors run"
    (let [log (atom [])
          interceptor {:id ::lifecycle-logger
                       :before-dispatch
                       (fn [ctx]
                         (swap! log conj [:before-dispatch (:actions ctx)])
                         ctx)
                       :after-dispatch
                       (fn [ctx]
                         (swap! log conj [:after-dispatch (count (:results ctx))])
                         ctx)}
          registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :done)}}
                    ::s/interceptors [interceptor]}
          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::noop]])

      (is (= 2 (count @log)))
      (is (= :before-dispatch (first (first @log))))
      (is (= :after-dispatch (first (second @log))))))

  (testing "interceptors run in order (before) and reverse (after)"
    (let [log (atom [])
          make-interceptor (fn [id]
                             {:id id
                              :before-dispatch
                              (fn [ctx]
                                (swap! log conj [:before id])
                                ctx)
                              :after-dispatch
                              (fn [ctx]
                                (swap! log conj [:after id])
                                ctx)})
          registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :done)}}
                    ::s/interceptors [(make-interceptor :a)
                                      (make-interceptor :b)
                                      (make-interceptor :c)]}
          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::noop]])

      ;; Before runs in order: a, b, c
      ;; After runs in reverse: c, b, a
      (is (= [[:before :a] [:before :b] [:before :c]
              [:after :c] [:after :b] [:after :a]]
             @log)))))

(deftest interceptor-effect-lifecycle-test
  (testing "before-effect and after-effect interceptors run for each effect"
    (let [log (atom [])
          interceptor {:id ::effect-logger
                       :before-effect
                       (fn [ctx]
                         (swap! log conj [:before-effect (:effect ctx)])
                         ctx)
                       :after-effect
                       (fn [ctx]
                         (swap! log conj [:after-effect (:effect ctx)])
                         ctx)}
          registry {::s/effects
                    {::fx1 {::s/handler (fn [_ _] :one)}
                     ::fx2 {::s/handler (fn [_ _] :two)}}
                    ::s/interceptors [interceptor]}
          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::fx1] [::fx2]])

      ;; Should have before/after for each effect
      (is (= 4 (count @log)))
      (is (= [::fx1] (second (nth @log 0))))  ;; before fx1
      (is (= [::fx1] (second (nth @log 1))))  ;; after fx1
      (is (= [::fx2] (second (nth @log 2))))  ;; before fx2
      (is (= [::fx2] (second (nth @log 3)))))))  ;; after fx2

(deftest interceptor-action-lifecycle-test
  (testing "before-action and after-action interceptors run for each action"
    (let [log (atom [])
          interceptor {:id ::action-logger
                       :before-action
                       (fn [ctx]
                         (swap! log conj [:before-action (:action ctx)])
                         ctx)
                       :after-action
                       (fn [ctx]
                         (swap! log conj [:after-action (:action ctx)])
                         ctx)}
          registry {::s/effects
                    {::log {::s/handler (fn [_ _ msg] {:logged msg})}}
                    ::s/actions
                    {::greet {::s/handler (fn [_state name]
                                            [[::log (str "Hello, " name)]])}}
                    ::s/interceptors [interceptor]}
          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::greet "World"]])

      ;; Should have before/after for the action
      (is (= 2 (count @log)))
      (is (= [:before-action [::greet "World"]] (first @log)))
      (is (= [:after-action [::greet "World"]] (second @log))))))

(deftest interceptor-context-modification-test
  (testing "interceptors can modify context"
    (let [interceptor {:id ::context-modifier
                       :before-dispatch
                       (fn [ctx]
                         (assoc ctx ::custom-data "injected"))
                       :after-dispatch
                       (fn [ctx]
                         (update ctx :results conj {:injected (::custom-data ctx)}))}
          registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :done)}}
                    ::s/interceptors [interceptor]}
          dispatch (s/create-dispatch [registry])
          {:keys [results]} (dispatch {} {} [[::noop]])]

      ;; After interceptor should have added the injected data
      (is (= {:injected "injected"} (last results))))))

(deftest fail-fast-interceptor-test
  (testing "fail-fast stops on first error"
    (let [log (atom [])
          registry {::s/effects
                    {::ok {::s/handler (fn [_ _ msg]
                                         (swap! log conj msg)
                                         :ok)}
                     ::fail {::s/handler (fn [_ _]
                                           (throw (ex-info "Boom!" {})))}}
                    ::s/interceptors [interceptors/fail-fast]}
          dispatch (s/create-dispatch [registry])
          {:keys [results errors]} (dispatch {} {} [[::ok "first"]
                                                    [::fail]
                                                    [::ok "third"]])]

      ;; Without fail-fast, "third" would run. With fail-fast, it shouldn't.
      (is (= ["first"] @log))
      (is (= 1 (count results)))
      (is (= 1 (count errors)))))

  (testing "fail-fast allows success when no errors"
    (let [log (atom [])
          registry {::s/effects
                    {::ok {::s/handler (fn [_ _ msg]
                                         (swap! log conj msg)
                                         :ok)}}
                    ::s/interceptors [interceptors/fail-fast]}
          dispatch (s/create-dispatch [registry])
          {:keys [results errors]} (dispatch {} {} [[::ok "first"]
                                                    [::ok "second"]
                                                    [::ok "third"]])]

      (is (= ["first" "second" "third"] @log))
      (is (= 3 (count results)))
      (is (empty? errors)))))

(deftest interceptor-error-handling-test
  (testing "interceptor errors are collected"
    (let [bad-interceptor {:id ::bad-interceptor
                           :before-dispatch
                           (fn [_ctx]
                             (throw (ex-info "Interceptor error" {})))}
          registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :done)}}
                    ::s/interceptors [bad-interceptor]}
          dispatch (s/create-dispatch [registry])
          {:keys [errors]} (dispatch {} {} [[::noop]])]

      ;; The interceptor error should be collected
      (is (= 1 (count errors)))
      (is (= ::bad-interceptor (:interceptor-id (first errors)))))))

;; =============================================================================
;; Phase 4: Discoverability Tests
;; =============================================================================

(def discoverability-registry
  "Registry with various items for testing discoverability."
  {::s/effects
   {:myapp/log
    {::s/description "Log a message to stdout"
     ::s/schema [:tuple [:= :myapp/log] :string]
     ::s/handler (fn [_ctx _sys msg] (println msg))}

    :myapp/save
    {::s/description "Save data to the database"
     ::s/schema [:tuple [:= :myapp/save] :map]
     ::s/system-keys [:db]
     ::s/handler (fn [_ctx _sys data] {:saved data})
     ;; User-defined metadata
     :deprecated true}}

   ::s/actions
   {:myapp/greet
    {::s/description "Greet a user by name"
     ::s/schema [:tuple [:= :myapp/greet] :string]
     ::s/handler (fn [_state name]
                   [[:myapp/log (str "Hello, " name "!")]])}}

   ::s/placeholders
   {:myapp/user-id
    {::s/description "Current user ID from dispatch context"
     ::s/schema :int
     ::s/handler (fn [dd] (:user-id dd))}}})

(deftest describe-all-test
  (testing "describe returns all items"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          items (s/describe dispatch)]
      (is (= 4 (count items)))
      ;; Check we have all types
      (is (= #{:effect :action :placeholder}
             (set (map ::s/type items)))))))

(deftest describe-by-type-test
  (testing "describe :effects returns only effects"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          items (s/describe dispatch :effects)]
      (is (= 2 (count items)))
      (is (every? #(= :effect (::s/type %)) items))))

  (testing "describe :actions returns only actions"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          items (s/describe dispatch :actions)]
      (is (= 1 (count items)))
      (is (= :action (::s/type (first items))))))

  (testing "describe :placeholders returns only placeholders"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          items (s/describe dispatch :placeholders)]
      (is (= 1 (count items)))
      (is (= :placeholder (::s/type (first items)))))))

(deftest describe-by-key-test
  (testing "describe with specific key returns single item"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          item (s/describe dispatch :myapp/save)]
      (is (map? item))
      (is (= :myapp/save (::s/key item)))
      (is (= :effect (::s/type item)))
      (is (= "Save data to the database" (::s/description item)))
      (is (= [:tuple [:= :myapp/save] :map] (::s/schema item)))
      (is (= [:db] (::s/system-keys item)))
      ;; User-defined metadata should be included
      (is (true? (:deprecated item)))))

  (testing "describe with unknown key returns nil"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          item (s/describe dispatch :unknown/key)]
      (is (nil? item)))))

(deftest sample-test
  (testing "sample generates single sample from schema"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          sample (s/sample dispatch :myapp/log)]
      (is (vector? sample))
      (is (= :myapp/log (first sample)))
      (is (string? (second sample)))))

  (testing "sample generates multiple samples"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          samples (s/sample dispatch :myapp/log 3)]
      (is (seq? samples))
      (is (= 3 (count samples)))
      (is (every? #(= :myapp/log (first %)) samples))))

  (testing "sample returns nil for item without schema"
    (let [registry {::s/effects
                    {::no-schema {::s/handler (fn [_ _] :done)}}}
          dispatch (s/create-dispatch [registry])
          sample (s/sample dispatch ::no-schema)]
      (is (nil? sample)))))

(deftest grep-test
  (testing "grep finds items by description (case-insensitive)"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          results (s/grep dispatch "database")]
      (is (= 1 (count results)))
      (is (= :myapp/save (::s/key (first results))))))

  (testing "grep finds items by key"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          results (s/grep dispatch "greet")]
      (is (= 1 (count results)))
      (is (= :myapp/greet (::s/key (first results))))))

  (testing "grep with regex pattern"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          results (s/grep dispatch #"log|greet")]
      (is (= 2 (count results)))
      (is (= #{:myapp/log :myapp/greet}
             (set (map ::s/key results))))))

  (testing "grep returns empty for no matches"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          results (s/grep dispatch "nonexistent")]
      (is (empty? results)))))

;; =============================================================================
;; Deep Grep Tests
;; =============================================================================

(def deep-grep-registry
  "Registry for testing deep grep functionality."
  {::s/effects
   {:stock/analyze
    {::s/description "Analyze stock performance"
     ::s/schema [:tuple
                 [:= :stock/analyze]
                 [:map
                  [:ticker {:description "Stock ticker symbol (e.g., AAPL, GOOG)"} :string]
                  [:threshold {:description "Alert threshold percentage"} :double]]]
     ::s/handler (fn [_ _ _] :analyzed)}

    :email/send
    {::s/description "Send an email notification"
     ::s/schema [:tuple
                 [:= :email/send]
                 [:map
                  [:to {:description "Recipient email address"} :string]
                  [:subject :string]
                  [:body :string]]]
     ::s/handler (fn [_ _ _] :sent)
     ;; Library-provided metadata
     :mylib/returns {:type :confirmation :fields [:message-id :timestamp]}
     :mylib/examples [{:desc "Send welcome email"
                       :effect [:email/send {:to "user@example.com"
                                             :subject "Welcome!"
                                             :body "Hello there"}]}]}}

   ::s/actions
   {:workflow/process
    {::s/description "Process a workflow"
     ::s/schema [:tuple [:= :workflow/process] :keyword]
     ::s/handler (fn [_ _] [])
     ;; Nested metadata with searchable content
     :mylib/tags #{:automation :batch-processing}}}})

(deftest deep-grep-schema-description-test
  (testing "grep finds effect by Malli schema parameter description"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "ticker symbol")]
      (is (= 1 (count results)))
      (is (= :stock/analyze (::s/key (first results))))))

  (testing "grep finds effect by another parameter description"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "threshold percentage")]
      (is (= 1 (count results)))
      (is (= :stock/analyze (::s/key (first results))))))

  (testing "grep finds effect by recipient description"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "recipient email")]
      (is (= 1 (count results)))
      (is (= :email/send (::s/key (first results)))))))

(deftest deep-grep-library-metadata-test
  (testing "grep finds effect by library-provided returns metadata"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "confirmation")]
      (is (= 1 (count results)))
      (is (= :email/send (::s/key (first results))))))

  (testing "grep finds effect by example content"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "welcome email")]
      (is (= 1 (count results)))
      (is (= :email/send (::s/key (first results))))))

  (testing "grep finds action by tag in set"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "automation")]
      (is (= 1 (count results)))
      (is (= :workflow/process (::s/key (first results))))))

  (testing "grep finds action by another tag"
    (let [dispatch (s/create-dispatch [deep-grep-registry])
          results (s/grep dispatch "batch-processing")]
      (is (= 1 (count results)))
      (is (= :workflow/process (::s/key (first results)))))))

(deftest schemas-test
  (testing "schemas returns map of all schemas"
    (let [dispatch (s/create-dispatch [discoverability-registry])
          schema-map (s/schemas dispatch)]
      (is (map? schema-map))
      (is (= 4 (count schema-map)))
      (is (= [:tuple [:= :myapp/log] :string] (get schema-map :myapp/log)))
      (is (= [:tuple [:= :myapp/save] :map] (get schema-map :myapp/save)))
      (is (= [:tuple [:= :myapp/greet] :string] (get schema-map :myapp/greet)))
      (is (= :int (get schema-map :myapp/user-id)))))

  (testing "schemas omits items without schemas"
    (let [registry {::s/effects
                    {::with-schema {::s/schema [:tuple [:= ::with-schema]]
                                    ::s/handler (fn [_ _] :done)}
                     ::without-schema {::s/handler (fn [_ _] :done)}}}
          dispatch (s/create-dispatch [registry])
          schema-map (s/schemas dispatch)]
      (is (= 1 (count schema-map)))
      (is (contains? schema-map ::with-schema))
      (is (not (contains? schema-map ::without-schema))))))

(deftest schema-constants-test
  (testing "EffectVector schema is accessible"
    (is (= [:vector [:cat :qualified-keyword [:* :any]]] s/EffectVector)))

  (testing "EffectsVector schema is accessible"
    (is (= [:vector s/EffectVector] s/EffectsVector))))

;; =============================================================================
;; Phase 5: System Schema Tests
;; =============================================================================

;; =============================================================================
;; Phase 6: Continuous Context Flow Tests
;; =============================================================================

(deftest before-dispatch-can-modify-dispatch-data
  (testing "Effect handler sees dispatch-data modified by before-dispatch interceptor"
    (let [interceptor {:before-dispatch
                       (fn [ctx]
                         (assoc-in ctx [:dispatch-data :injected] :by-interceptor))}

          effect-received (atom nil)

          registry {::s/interceptors [interceptor]
                    ::s/effects
                    {::capture
                     {::s/handler
                      (fn [{:keys [dispatch-data]} _]
                        (reset! effect-received dispatch-data))}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::capture]])

      (is (= :by-interceptor (:injected @effect-received))
          "Effect handler should see dispatch-data modified by before-dispatch interceptor"))))

(deftest before-action-can-access-dispatch-data
  (testing "before-action interceptor sees dispatch-data"
    (let [seen-dispatch-data (atom nil)
          interceptor {:before-action
                       (fn [ctx]
                         (reset! seen-dispatch-data (:dispatch-data ctx))
                         ctx)}

          registry {::s/interceptors [interceptor]
                    ::s/actions
                    {::test-action
                     {::s/handler
                      (fn [_state] [[::noop]])}}
                    ::s/effects
                    {::noop
                     {::s/handler (fn [_ _])}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {:my-key :my-value} [[::test-action]])

      (is (= {:my-key :my-value} @seen-dispatch-data)
          "before-action interceptor should see dispatch-data"))))

(deftest before-effect-modifications-propagate
  (testing "Each before-effect sees prior effect's dispatch-data modifications"
    (let [call-order (atom [])
          interceptor {:before-effect
                       (fn [ctx]
                         (swap! call-order conj (:dispatch-data ctx))
                         (update-in ctx [:dispatch-data :counter] (fnil inc 0)))}

          registry {::s/interceptors [interceptor]
                    ::s/effects
                    {::noop {::s/handler (fn [_ _])}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::noop] [::noop] [::noop]])

      (is (= [{} {:counter 1} {:counter 2}] @call-order)
          "Each before-effect should see prior effect's modifications"))))

(deftest dispatch-data-modifications-dont-leak-between-dispatches
  (testing "Modifications don't leak between separate dispatches"
    (let [interceptor {:before-dispatch
                       (fn [ctx]
                         (assoc-in ctx [:dispatch-data :modified] true))}

          effect-received (atom nil)

          registry {::s/interceptors [interceptor]
                    ::s/effects
                    {::capture
                     {::s/handler
                      (fn [{:keys [dispatch-data]} _]
                        (reset! effect-received dispatch-data))}}}

          dispatch (s/create-dispatch [registry])]

      ;; First dispatch with empty dispatch-data
      (dispatch {} {} [[::capture]])
      (is (= {:modified true} @effect-received))

      ;; Second dispatch should start fresh
      (reset! effect-received nil)
      (dispatch {} {:other :data} [[::capture]])
      (is (= {:other :data :modified true} @effect-received)
          "Second dispatch should start with provided dispatch-data, not prior modifications"))))

;;; After-phase tests

(deftest after-action-sees-produced-actions
  (testing "after-action sees effects produced by action handler"
    (let [seen-actions (atom nil)
          interceptor {:after-action
                       (fn [ctx]
                         (reset! seen-actions (:actions ctx))
                         ctx)}

          registry {::s/interceptors [interceptor]
                    ::s/actions
                    {::produce-effects
                     {::s/handler
                      (fn [_state]
                        [[::effect-a] [::effect-b]])}}
                    ::s/effects
                    {::effect-a {::s/handler (fn [_ _])}
                     ::effect-b {::s/handler (fn [_ _])}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::produce-effects]])

      (is (= [[::effect-a] [::effect-b]] @seen-actions)
          "after-action should see effects produced by action handler"))))

(deftest after-effect-sees-result
  (testing "after-effect sees return value from effect handler"
    (let [seen-result (atom nil)
          interceptor {:after-effect
                       (fn [ctx]
                         (reset! seen-result (:result ctx))
                         ctx)}

          registry {::s/interceptors [interceptor]
                    ::s/effects
                    {::return-value
                     {::s/handler
                      (fn [_ _] {:status :ok :data 42})}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::return-value]])

      (is (= {:status :ok :data 42} @seen-result)
          "after-effect should see return value from effect handler"))))

(deftest after-dispatch-sees-accumulated-results
  (testing "after-dispatch sees all effect results"
    (let [seen-results (atom nil)
          interceptor {:after-dispatch
                       (fn [ctx]
                         (reset! seen-results (:results ctx))
                         ctx)}

          registry {::s/interceptors [interceptor]
                    ::s/effects
                    {::fx-a {::s/handler (fn [_ _] :result-a)}
                     ::fx-b {::s/handler (fn [_ _] :result-b)}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::fx-a] [::fx-b]])

      (is (= [{:effect [::fx-a] :res :result-a}
              {:effect [::fx-b] :res :result-b}]
             @seen-results)
          "after-dispatch should see all effect results"))))

(deftest after-interceptors-run-in-lifo-order
  (testing "after-action interceptors run in reverse order (LIFO)"
    (let [call-order (atom [])
          interceptor-a {:id :a
                         :after-action #(do (swap! call-order conj :a) %)}
          interceptor-b {:id :b
                         :after-action #(do (swap! call-order conj :b) %)}
          interceptor-c {:id :c
                         :after-action #(do (swap! call-order conj :c) %)}

          registry {::s/interceptors [interceptor-a interceptor-b interceptor-c]
                    ::s/actions
                    {::test {::s/handler (fn [_] [[::noop]])}}
                    ::s/effects
                    {::noop {::s/handler (fn [_ _])}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::test]])

      (is (= [:c :b :a] @call-order)
          "after-action interceptors should run in reverse order (LIFO)"))))

(deftest after-phase-modifications-propagate-between-interceptors
  (testing "Outer after-interceptor sees modifications from inner"
    (let [seen-by-outer (atom nil)
          inner-interceptor {:id :inner
                             :after-action
                             (fn [ctx]
                               (assoc ctx :added-by-inner :hello))}
          outer-interceptor {:id :outer
                             :after-action
                             (fn [ctx]
                               (reset! seen-by-outer (:added-by-inner ctx))
                               ctx)}

          ;; outer is first in vector, so runs last in after-action (LIFO)
          registry {::s/interceptors [outer-interceptor inner-interceptor]
                    ::s/actions
                    {::test {::s/handler (fn [_] [[::noop]])}}
                    ::s/effects
                    {::noop {::s/handler (fn [_ _])}}}

          dispatch (s/create-dispatch [registry])]

      (dispatch {} {} [[::test]])

      (is (= :hello @seen-by-outer)
          "Outer after-interceptor should see modifications from inner"))))

;; =============================================================================
;; Phase 5: System Schema Tests
;; =============================================================================

(deftest system-schema-test
  (testing "system-schema returns merged schema from registries"
    (let [db-registry {::s/effects
                       {:db/execute {::s/handler (fn [_ _ _] :done)}}
                       ::s/system-schema
                       {:datasource [:map [:uri :string]]}}
          auth-registry {::s/effects
                         {:auth/check {::s/handler (fn [_ _ _] :done)}}
                         ::s/system-schema
                         {:jwt-secret :string}}
          dispatch (s/create-dispatch [db-registry auth-registry])
          sys-schema (s/system-schema dispatch)]
      (is (map? sys-schema))
      (is (= [:map [:uri :string]] (:datasource sys-schema)))
      (is (= :string (:jwt-secret sys-schema)))))

  (testing "system-schema returns nil when no schema declared"
    (let [registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :done)}}}
          dispatch (s/create-dispatch [registry])]
      (is (nil? (s/system-schema dispatch)))))

  (testing "later registry overwrites conflicting system-schema keys"
    (let [registry-a {::s/system-schema {:db [:map [:v1 :string]]}}
          registry-b {::s/system-schema {:db [:map [:v2 :int]]}}
          dispatch (s/create-dispatch [registry-a registry-b])
          sys-schema (s/system-schema dispatch)]
      ;; Later registry wins
      (is (= [:map [:v2 :int]] (:db sys-schema)))))

  (testing "system-keys on effects are included in describe"
    (let [registry {::s/effects
                    {:db/query {::s/description "Query database"
                                ::s/system-keys [:datasource :cache]
                                ::s/handler (fn [_ _ _] :done)}}}
          dispatch (s/create-dispatch [registry])
          desc (s/describe dispatch :db/query)]
      (is (= [:datasource :cache] (::s/system-keys desc))))))

;; =============================================================================
;; Dispatch Initializer Tests
;; =============================================================================

(deftest dispatch-initializer-test
  (testing "creates dispatch from config map"
    (let [registry {::s/effects
                    {::noop {::s/handler (fn [_ _] :ok)}}}
          d (s/dispatch {:registries [registry]})]
      (is (instance? ascolais.sandestin.Dispatch d))
      (is (= [{:effect [::noop] :res :ok}]
             (:results (d {} {} [[::noop]]))))))

  (testing "works with multiple registries"
    (let [reg-a {::s/effects
                 {::fx-a {::s/handler (fn [_ _] :a)}}}
          reg-b {::s/effects
                 {::fx-b {::s/handler (fn [_ _] :b)}}}
          d (s/dispatch {:registries [reg-a reg-b]})]
      (is (= :a (:res (first (:results (d {} {} [[::fx-a]]))))))
      (is (= :b (:res (first (:results (d {} {} [[::fx-b]]))))))))

  (testing "supports registry function vectors"
    (let [make-registry (fn [value]
                          {::s/effects
                           {::configured {::s/handler
                                          (fn [_ _] value)}}})
          d (s/dispatch {:registries [[make-registry :configured-value]]})]
      (is (= :configured-value
             (:res (first (:results (d {} {} [[::configured]])))))))))
