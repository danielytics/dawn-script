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

#_
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


(deftest evaluate-triggers-test
  (testing "apply triggers when condition matches a constant"
    (is (=  "test"
            (:current-state (dawn/-evaluate-triggers {} {:trigger [{:when     true
                                                                    :to-state "test"}]})))))
  
  (testing "apply triggers when condition matches a formula"
    (is (=  "test"
            (:current-state (dawn/-evaluate-triggers {} {:trigger [{:when     (types/formula {:ast [:binary-op :> [:integer 10] [:integer 1]]})
                                                                    :to-state "test"}]})))))
  
  (testing "don't apply triggers when condition does not match"
    (is (nil? (:current-state (dawn/-evaluate-triggers {} {:trigger [{:when     false
                                                                      :to-state "test"}]})))))
  
  (testing "apply triggers when condition does not match a formula"
    (is (nil? (:current-state (dawn/-evaluate-triggers {} {:trigger [{:when     (types/formula {:ast [:binary-op :> [:integer 1] [:integer 10]]})
                                                                      :to-state "test"}]})))))
  
  ; TODO: Should orders only be supressed when states change? Not just when triggers match
  (testing "do not generate orders when trigger matches"
    (is (empty? (:orders (dawn/-evaluate-triggers
                          {}
                          {:trigger [{:when true}]
                           :orders  [{:tag "test"}]}))))))

(deftest apply-state-test
  (testing "does not abort when state doesn't do anything"
    (let [result (dawn/-apply-state {} {})]
      (is (not (reduced? result)))))

  (testing "notes are applied"
    (let [result (dawn/-apply-state {} {:note {:text "hi"}})]
      (is (not (reduced? result)))
      (is (= {:messages [{:category :note
                          :text     "hi"}]
              :data {}
              :orders {}}
           (update result :messages (partial mapv #(dissoc % :time)))))))

  (testing "orders are applied"
    (let [result (dawn/-apply-state {} {:orders [{:tag "foo" :type "market" :side "buy" :contracts 100}
                                                 {:tag "bar" :type "market" :side "sell" :contracts (types/formula {:ast [:binary-op :* [:integer 50] [:integer 4]]})}]})]
      (is (not (reduced? result)))
      (is (= {:data   {}
              :orders {"foo" {:type      "market"
                              :side      "buy"
                              :contracts 100
                              :tag       "foo"}
                       "bar" {:type      "market"
                              :side      "sell"
                              :contracts 200
                              :tag       "bar"}}}
             result))))

  (testing "data is applied"
    (let [result (dawn/-apply-state {} {:data {:a 100 :b (types/formula {:ast [:binary-op :+ [:integer 10] [:integer 2]]})}})]
      (is (not (reduced? result)))
      (is (= {:data   {:a 100 :b 12}
              :orders {}}
             result)))

    (testing "triggers are applied after data"
      (let [result (dawn/-apply-state {:current-state "foo"} {:data    {:a 1}
                                                              :trigger [{:when true
                                                                         :data {:a 2}}]})]
        (is (not (reduced? result)))
        (is (= {:data   {:a 2}
                :current-state "foo"
                :orders {}}
               result))))
  
    (testing "triggers that change state abort the reduction"
      (let [result (dawn/-apply-state {:current-state "foo"} {:data    {:a 1}
                                                              :trigger [{:when true
                                                                         :to-state "bar"
                                                                         :data {:a 2}}]})]
        (is (reduced? result))
        (is (= {:data          {:a 2}
                :messages [{:category :info :text "Transitioning state to: bar"}]
                :current-state "bar"
                :orders {}}
               (update (unreduced result) :messages (partial mapv #(dissoc % :time)))))))))

(deftest execution-pass-test
  (testing ""))
