(ns dawn.core-test
  (:require [clojure.test :refer :all]
            [dawn.core :as dawn]
            [dawn.types :as types]))

; Sanity checks to make sure the TOML source gets loaded as expected and
; that the sructure doesn't accidentally get changed
(deftest load-strategy-sanity-test
  (let [strategy (dawn/load-string "name = \"test\"
                                      [[config]]
                                        name = \"c1\"
                                        label = \"config 1\"
                                        type = \"number\"
                                        default = 11
                                      [[config]]
                                        name = \"c2\"
                                        label = \"config 2\"
                                        type = \"number\"
                                        default = 22
                                    
                                    [inputs]
                                      i1 = { type = \"number\"
                                    
                                    [data]
                                      d1 = 33
                                    
                                    [states]
                                      initial = \"start-state\"
                                    
                                      [[states.state]]
                                         id = \"start-state\"
                                        data.state-data-1 = 123
                                        # TODO: Add triggers and orders
                                        ")]
    (testing "config sanity checks"
      (let [config (group-by :name (:config strategy))]
        (is (= 11
               (get-in config ["c1" 0 :default])))
        (is (= 22
               (get-in config ["c2" 0 :default])))))
    
    (testing "inputs sanity checks"
      (let [inputs (:inputs strategy)]
        (is (= "number"
               (get-in inputs [:i1 :type])))))
    
    (testing "data sanity checks"
      (let [data (:initial-data strategy)]
        (is (= 33
               (:d1 data)))))
    
    (testing "states sanity checks"
      (let [states (:states-by-id strategy)]
        (is (= "start-state"
               (get-in strategy [:states :initial])))
        (is (= "start-state"
               (get-in states ["start-state" :id])))
        (is (= 123
               (get-in states ["start-state" :data :state-data-1])))))))


(defn make-instance
  [{:keys [inputs account config candle orderbook]}]
  {:inputs (merge {:in1 0
                   :in2 1}
                  inputs)
   :accounts (merge {:balance 1000
                     :leverage 1}
                    account)
   :config (merge {:con1 10}
                  config)
   :exchange {:candle (merge {:open 100
                              :high 110
                              :low 90
                              :close 105
                              :volume 1000}
                             candle)
              :orderbook (merge {:paice {:ask 110
                                         :bid 100}
                                 :volume {:ask 1000
                                          :bid 100}}
                                orderbook)}
   :orders {}
   :data {}})

(deftest strategy-integration-test
  (let [strategy (dawn/load-string "[data]
                                      var1 = 1
                                      counter = 1
                                    
                                    [states]
                                      initial = \"start-state\"
                                      [[states.state]]
                                        id = \"start-state\"
                                        data.var1 = 2
                                        data.var2 = 10
                                        data.counter = \"=> #counter + 1\"
                                        note.text = \"=> [text: ['Var1:', #var1]]\"
                                        [[states.state.trigger]]
                                          when = \"=> #counter < 3\"
                                          to-state = \"end-state\"
                                        [[states.state]]
                                          id = \"end-state\"
                                          note.text = \"=> [text: ['Var1:', #var1, ' Counter:', #counter]]\"")]
    (testing "execute strategy"
      (let [result (dawn/execute strategy (make-instance {}))]
        (is (= ["Executing state: start-state"
                "Var1:1"
                "Transitioning state to: end-state"
                "Executing state: end-state"
                "Var1:2 Counter:2"]
               (mapv :text (:messages result))))))))
