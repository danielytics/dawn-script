(ns dawn.runtime
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [dawn.builtins :as builtins]
            [dawn.evaluator :as evaluator]
            [dawn.types :as types]))

(defn -eval
  [context value]
  (if (types/formula? value)
    (evaluator/evaluate
     (update context :static merge (:functions (types/vars value)))
     (types/ast value))
    value))

(defn -generate-binding-combos
  [var-bindings]
  (reduce
    (fn [accum [k next]]
      (for [a accum
             b next]
        (assoc a k b)))
   [{}]
   var-bindings))

(defn -make-kv-evaluator
  [context]
  (fn [[key value]]
    [key (-eval context value)]))

(defn -evaluate-order
  "Evaluate the when expression and if true, evaluate each field of the order"
  [context order]
  (when (-eval context (:when order))
    (->> (dissoc order :when)
         (map (fn [[k v]] [k (-eval context v)]))
         (into {}))))

(defn -evaluate-orders
  "Generate a list of orders by evaluating the appropriate fields of orders and expanding foreach statemens"
  [context orders]
  (let [eval-kv (-make-kv-evaluator context)]
    (remove
     nil?
     (reduce
      (fn [all-orders order-template]
        (if-let [foreach (:foreach order-template)]
          (into all-orders (for [var-bindings (-generate-binding-combos (map eval-kv foreach))]
                             (-evaluate-order
                              (update context :data merge var-bindings)
                              (dissoc order-template :foreach))))
          (conj all-orders (-evaluate-order context order-template))))
      []
      orders))))

(defn -order->effect
  [context order]
  (let [order-status (get-in context [:all-orders (:tag order) :status])
        order (update order :contracts (fnil long 0))]
    (cond
      (not= order-status "open")
      (assoc order :effect/name :place-order)
      
      (= order-status "open")
      {:effect/name :edit-order
       :tag         (:tag order)
       :price       (:price order)
       :contracts   (:contracts order)})))

(defn -check-trigger-fn
  [context]
  (fn [{condition :when :as trigger}]
    (when (-eval context condition)
      (dissoc trigger :condition))))

(defn -add-message
  [context category message]
  (update context :messages conj {:category category
                                  :time     nil
                                  :text     message}))

(defn -process-trigger-action
  [context trigger-action]
  (println "Need to perform trigger action:" trigger-action)
  (let [new-state (:to-state trigger-action)]
  ; Need to retract orders here by creating actions
    (cond-> {:context  (assoc context :current-state (or new-state (:current-state context)))
             :messages []
             :data     (into (:data context)
                             (map (fn [[k v]] [k (-eval context v)]) (:data trigger-action)))}
      (seq new-state) (-add-message :info (str "Transitioning state to: " new-state))
      (:note trigger-action) (-add-message (get-in trigger-action [:note :category] :note)
                                           (get-in trigger-action [:note :text])))))

(defn -process-orders
  [context orders]
  (let [orders  (-evaluate-orders context orders)
        actions (for [order orders]
                  (-order->effect context order))]
    (println "Current state:" (:current-state context))
    (println "Orders: " orders)
    (println "Data:" (:data context))
    (println "Actions:")
    (clojure.pprint/pprint actions)
    {:context context
     :data    (:data context)
     :actions actions}))

(defn -execute
  [context state]
  (let [context (if (:new-state? context)
                  (update context :data merge (:data state))
                  context)]
    (if-let [trigger-action (some (-check-trigger-fn context) (:triggers state))]
      (-process-trigger-action context trigger-action)
      (-process-orders context (:orders state)))))

; TODO: nested state
(defn -run-execution-loop
  [initial-state states context]
    (loop [visited-states #{initial-state}
          context         context]
      (let [current-state                  (:current-state context)
            results                        (-> context
                                               (-add-message :info (str "Executing state: " current-state))
                                               (-execute (get states current-state)))
            previous-state                 current-state
            context                        (:context results)
            current-state                  (:current-state context)
            context                        (-> context
                                               (assoc :data (assoc (:data results) :dawn/state [current-state]))
                                               (update :messages into (:messages results))
                                               (update :actions into (:actions results)))]
        (if (not= previous-state current-state)
          (if (contains? visited-states current-state)
            (-add-message context :warning (str "Loop detected: " initial-state " -> ... -> " previous-state " -> " current-state))
            (recur (conj visited-states current-state)
                   (assoc context :new-state? true)))
          context))))

(defn execute
    [{:keys [initial-data states]} {:keys [inputs config account exchange data orders]}]
  (let [previous-state (peek (:dawn/state data))
        data           (if previous-state data initial-data)
        current-state  (peek (:dawn/state data))
        initial-state  (or previous-state current-state)
        context        {:static        {:inputs   inputs
                                        :config   config
                                        :account  account
                                        :exchange exchange}
                        :libs          builtins/libraries
                        :orders        orders
                        :messages      []
                        :actions       []
                        :current-state current-state
                        :new-state?    (not= previous-state current-state)
                        :data          (dissoc data :dawn/state)}
        results        (-run-execution-loop initial-state states context)]
    {:actions (:actions results)
     :messages (:messages results)
     :data (:data results)}))
