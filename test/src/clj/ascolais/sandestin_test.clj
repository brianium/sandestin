(ns ascolais.sandestin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sandestin :as s]
            [ascolais.sandestin.registry :as registry]))

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
      (is (= ::unknown-effect
             (first (:effect (first errors))))))))

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
