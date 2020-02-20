(ns dawn.runtime-test
  (:require [clojure.test :refer :all]
            [dawn.runtime :as dawn]
            [dawn.types :as types]))

(deftest binding-combination-test
  (testing "binding combinations are generated as expected"
    (is (= #{{:a 1 :b 1}
             {:a 1 :b 2}
             {:a 1 :b 3}
             {:a 2 :b 1}
             {:a 2 :b 2}
             {:a 2 :b 3}}
           (set (dawn/-generate-binding-combos {:a [1 2]
                                                :b [1 2 3]}))))))

(deftest order-test
  (testing "evaluate expressions"
    (is (= "hello" (dawn/-eval {} "hello")))
    (is (= 12 (dawn/-eval {} (types/formula {:ast [:binary-op :+ [:integer 10] [:integer 2]]})))))
  
  (testing "evaluate key-value pairs"
    (is (= {:a "a"
            :b 12}
           (into {} (mapv
                     (dawn/-make-kv-evaluator {})
                     {:a "a"
                      :b (types/formula {:ast [:binary-op :+ [:integer 10] [:integer 2]]})})))))
  
  (testing "evaluate order"
    (is (= {:contracts 10}
           (dawn/-evaluate-order {} {:when (types/formula {:ast [:binary-op :> [:integer 4] [:integer 2]]})
                                     :contracts (types/formula {:ast [:binary-op :+ [:integer 7] [:integer 3]]})})))
    (is (nil?
           (dawn/-evaluate-order {} {:when      (types/formula {:ast [:binary-op :> [:integer 4] [:integer 5]]})
                                     :contracts (types/formula {:ast [:binary-op :+ [:integer 7] [:integer 3]]})})))))

