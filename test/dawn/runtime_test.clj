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
           (dawn/-evaluate-order {} {:when      (types/formula {:ast [:binary-op :> [:integer 4] [:integer 2]]})
                                     :contracts (types/formula {:ast [:binary-op :+ [:integer 7] [:integer 3]]})})))
    (is (nil?
         (dawn/-evaluate-order {} {:when      (types/formula {:ast [:binary-op :> [:integer 4] [:integer 5]]})
                                   :contracts (types/formula {:ast [:binary-op :+ [:integer 7] [:integer 3]]})})))))


(deftest order->effect-test
  (testing "new order"
    (let [context {:all-orders {}}
          order {:type :limit
                 :tag "foo"
                 :price 100
                 :contracts 10
                 :side :buy}]
      (is (= {:effect/name :place-order
              :tag "foo"
              :type :limit
              :price 100
              :contracts 10
              :side :buy}
             (dawn/-order->effect context order)))))
  
  (testing "edit existing order"
    (let [context {:all-orders {"foo" {:status "open"}}}
          order   {:type      :limit
                   :tag       "foo"
                   :price     100
                   :contracts 10
                   :side      :buy}]
      (is (= {:effect/name :edit-order
              :tag         "foo"
              :price       100
              :contracts   10}
             (dawn/-order->effect context order))))))


(deftest process-trigger-action-test
  (testing "adds data"
    (is {:data {:foo "foo"
                :bar "bar"}}
        (dawn/-process-trigger-action {:data {:bar "bar"}} {:data {:foo "foo"}})))
  
  (testing "adds note"
    (is (= [{:category :note
             :text     "Hello"}]
           (->> (dawn/-process-trigger-action {:messages []} {:note {:category :note
                                                                     :text     "Hello"}})
                :messages
                (mapv #(dissoc % :time))))))
  
  (testing "changes state"
    (is (= "new-state"
           (:current-state (dawn/-process-trigger-action {} {:to-state "new-state"}))))))


(deftest execute-state-test
  (testing "apply triggers when condition matches a constant"
    (is (=  "test"
            (:current-state (dawn/-execute-state {} {:trigger [{:when     true
                                                                :to-state "test"}]})))))
  
  (testing "apply triggers when condition matches a formula"
    (is (=  "test"
            (:current-state (dawn/-execute-state {} {:trigger [{:when     (types/formula {:ast [:binary-op :> [:integer 10] [:integer 1]]})
                                                                :to-state "test"}]})))))
  
  (testing "don't apply triggers when condition does not match"
    (is (nil? (:current-state (dawn/-execute-state {} {:trigger [{:when     false
                                                                  :to-state "test"}]})))))
  
  (testing "apply triggers when condition does not match a formula"
    (is (nil? (:current-state (dawn/-execute-state {} {:trigger [{:when     (types/formula {:ast [:binary-op :> [:integer 1] [:integer 10]]})
                                                                  :to-state "test"}]}))))))

