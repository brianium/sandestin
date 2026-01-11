(ns ascolais.sandestin-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sandestin :as sandestin]))

(deftest greet-test
  (testing "greet returns a greeting message"
    (is (= "Hello, World!" (sandestin/greet "World")))
    (is (= "Hello, Clojure!" (sandestin/greet "Clojure")))))
