(ns dawn.core-test
  (:require [clojure.test :refer :all]
            [dawn.core :as dawn]
            [dawn.types :as types]))

; Sanity checks to make sure the TOML source gets loaded as expected and
; that the sructure doesn't accidentally get changed
(deftest load-strategy-sanity-test
  (let [strategy (dawn/load-string "[[config]]
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
                                      i1 = { type = \"number\" }
                                    
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
      (let [states (:states strategy)]
        (is (= "start-state"
               (get-in strategy [:initial-data :dawn/state])))
        (is (= "start-state"
               (get-in states ["start-state" :id])))
        (is (= 123
               (get-in states ["start-state" :data :state-data-1])))))))


(defn make-instance
  [{:keys [inputs account config]}]
  [{:config (merge {:con1 10
                    :counter-start 1}
                   config)
    :data {}}
   {:inputs (merge {:in1 0
                    :in2 1}
                   inputs)
    :accounts (merge {:balance 1000
                      :leverage 1}
                     account)
    :orders {}}])

(def integration-test-source
  "[data]
    var1 = 1
    counter = \"=> config.counter-start\"
   
   [states]
    initial = \"start-state\"
    [[states.state]]
      id = \"start-state\"
      data.var1 = 2
      data.var2 = 10
      data.counter = \"=> #counter + 1\"
      note.text = \"=> [text: 'Var1:', #var1, ' Counter:', #counter]\"
      [[states.state.trigger]]
        when = \"=> #counter < 3\"
        to-state = \"end-state\"
      [[states.state.trigger]]
        when = \"=> #counter > 3\"
        to-state = \"child1\"
    [[states.state]]
      id = \"end-state\"
      note.text = \"=> [text: 'Var1:', #var1, ' Counter:', #counter]\"
    [[states.state]]
      id = \"parent\"
      data.var3 = 15
      data.counter = \"=> #counter + 1\"
    [[states.state]]
      id = \"child1\"
      parent = \"parent\"
      data.var4 = 88
      data.counter = \"=> #counter + 1 \"
      [[states.state.trigger]]
        when = true
        note.text = \"Leaving child state\"
        to-state = \"child2\"
    [[states.state]]
      id = \"child2\"
      parent = \"child\"
      data.counter = \"=> #counter + 1 \"
      [[states.state.trigger]]
        when = true
        note.text = \"To end-state!\"
        to-state = \"end-state\"")

(deftest strategy-integration-test
  (let [strategy (dawn/load-string integration-test-source)]
    (testing "execute strategy"
      (let [result (:result (apply dawn/execute strategy (make-instance {})))]
        (is (= ["Executing state: start-state"
                "Var1:1 Counter:1"
                "Transitioning state to: end-state"
                "Executing state: end-state"
                "Var1:2 Counter:2"]
               (mapv :text (:messages result))))))
    
    (testing "child states"
      (let [result (:result (apply dawn/execute strategy (make-instance {:config {:counter-start 4}})))]
        (is (= ["Executing state: start-state"
                 "Var1:1 Counter:4"
                 "Transitioning state to: child1"
                 "Executing state: child1"
                 "Leaving child state"
                 "Transitioning state to: child2"
                 "Executing state: child2"
                 "To end-state!"
                 "Transitioning state to: end-state"
                 "Executing state: end-state"
                 "Var1:2 Counter:8"]
               (mapv :text (:messages result))))))))

(def order-test-source
  "[inputs]
     candle_close = { type = \"price\" }
   [data]
      fill-price = 0
   [states]
      initial = \"not-in-position\"
      [[states.state]]
        id = \"not-in-position\"
        [[states.state.trigger]]
          when = \"=> inputs.in1 > 0\"
          to-state = \"in-position\"
        [[states.state.orders]]
          tag = \"test\"
          type = \"limit\"
          side = \"buy\"
          contracts = 100
          price = \"=> inputs.candle_close + 10\"
          on.fill.data.fill-price = \"=> event.fill-price\"
          on.fill.to-state = \"in-position\"
      [[states.state]]
        id = \"in-position\"
        [[states.state.orders]]
          tag = \"stop-loss\"
          type = \"stop-market\"
          trigger = \"=> #fill-price - 100\"
          instructions = [\"close\"]")

(deftest strategy-order-test
  (let [strategy (dawn/load-string order-test-source)
        instance (atom (make-instance {:inputs {:candle_close 10}}))
        advance! (fn [& [changes]] (swap! instance #(assoc % :data (get-in (dawn/execute strategy (merge-with merge % changes)) [:result :data]))))]
    (testing "creates order"
      (is (= {:fill-price 0
              :dawn/orders #{"test"}
              :dawn/state "not-in-position"}
             (:data (advance!)))))
    
    (testing "creates new orders on state change"
      (is (= {:fill-price 0
              :dawn/orders #{"stop-loss"}
              :dawn/state "in-position"}
             (:data (advance! {:inputs {:in1 1}})))))))

(def trigger-parent-state-data-source
  "[states]
     initial = \"start-state\"
     [[states.state]]
       id = \"parent-state\"
       data.a = 2
     [[states.state]]
       id = \"medium\"
       parent = \"parent-state\"
       data.a = 4
     [[states.state]]
       id = \"start-state\"
       parent = \"medium\"
       [[states.state.trigger]]
         when = \"=> #a > 3\"
         to-state = \"end-state\"
     [[states.state]]
        id = \"end-state\"")

(deftest trigger-based-on-parent-state-data-test
  (let [strategy (dawn/load-string trigger-parent-state-data-source)
        instance (make-instance {})]
    (testing "parent data is available in trigger"
      (is (= {:dawn/state "end-state"
              :dawn/orders #{}}
             (get-in (dawn/execute strategy instance) [:result :data]))))))

(deftest text-builtin-function-test
  (let [strategy (dawn/load-string "data.a = \"=> [text: 'a', 'b', 'c']\"
                                    data.b = \"=> [min: 8, 4, 6]\"")
        instance (make-instance {})]
    (testing "parent data is available in trigger"
      (is (= {:dawn/state nil
              :dawn/orders #{}
              :a "abc"
              :b 4}
             (get-in (dawn/execute strategy instance) [:result :data]))))))
