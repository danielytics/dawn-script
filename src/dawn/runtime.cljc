(ns dawn.runtime
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [dawn.builtins :as builtins]
            [dawn.evaluator :as evaluator]
            [dawn.types :as types]))

(defn -eval
  [context value]
  (if (types/formula? value)
    (try+
      (evaluator/evaluate
       (update context :static merge (:functions (types/vars value)))
       (types/ast value))
      (catch Object error
       (throw+ (assoc error
                      :object-path (types/keys value)
                      :source (types/source value)))))
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

(defn -kv-evaluate
  [context kv]
  (let [kv-eval (-make-kv-evaluator context)]
    (->> kv
         (map kv-eval)
         (into {}))))

(defn -evaluate-order
  "Evaluate the when expression and if true, evaluate each field of the order"
  [context order]
  (when (-eval context (get order :when true))
    (->> (dissoc order :when)
         (-kv-evaluate context))))

(defn -evaluate-orders
  "Generate a list of orders by evaluating the appropriate fields of orders and expanding foreach statemens"
  [context orders]
  (let [eval-kv (-make-kv-evaluator context)]
    (->> orders
         (reduce
          (fn [all-orders order-template]
            (if-let [foreach (:foreach order-template)]
              (into all-orders (for [var-bindings (-generate-binding-combos (map eval-kv foreach))]
                                 (-evaluate-order
                                  (update context :data merge var-bindings)
                                  (dissoc order-template :foreach))))
              (conj all-orders (-evaluate-order context order-template))))
          [])
         (remove nil?)
         (group-by :tag)
         (map (fn [[k v]] [k (reduce merge v)]))
         (into {}))))

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
  (let [category-kw (keyword category)]
    (if (contains? #{:warning :error :info :note :order} category-kw)
      (update context :messages conj {:category category-kw
                                      :time     nil
                                      :text     message})
      (-add-message context :warning (str "Tried to add note with invalid category '" category "': " message)))))

(defn -eval-message
  "Add note to messaegs, if required"
  [context note]
  (if (seq note)
    (-add-message context
                  (get note :category :note)
                  (-eval context (get note :text "")))
    context))

(defn -process-trigger-action
  [context trigger-action]
  (let [new-state (-eval context (:to-state trigger-action))]
    ; Need to retract orders here by creating actions
    (-> context
        (assoc :current-state (or new-state (:current-state context)))
        (update :data merge (-kv-evaluate context (:data trigger-action)))
        (-eval-message (:note trigger-action))
        (-eval-message (when new-state {:category :info
                                        :text (str "Transitioning state to: " new-state)})))))

(defn -process-orders
  [context orders]
  (let [orders (-evaluate-orders context orders)]
    (println "Orders:" orders)
    (update context :orders (partial merge-with merge) orders)))

(defn -execute-state
  [context state]
  (if-let [trigger-action (some (-check-trigger-fn context) (:trigger state))]
    (-process-trigger-action context trigger-action) 
    (-process-orders context (:orders state))))

(defn -check-state-change
  "Check if a state transition has been triggered, abort reduction if so"
  [{:keys [current-state] :as context} previous-state]
  (if (not= current-state previous-state)
    (reduced context)
    context))

(defn -apply-state
  [context state]
  (-> context
      (-eval-message (:note state))
      (update :data merge (-kv-evaluate context (:data state)))
      (-execute-state state)
      (-check-state-change (:current-state context))))

(defn -execute
  [context states {:keys [key] :as state}]
  (if (:new-state? context)
    (let [num-common (->> (get-in states [(:previous-state context) :key])
                          (map vector key)
                          (take-while #(apply = %))
                          (count))
          context    (-> context
                         (update :data select-keys (:variables state))
                         (assoc :orders {}))]
      (->> key
         ; Find the number of common parent keys (if any) and drop them
           (drop num-common)
         ; Convert state ID's to state maps
           (map #(get states %))
         ; Apply each not-in-common parent state to context in turn
         ; The current state is always at the end of the key, so will be applied also
           (reduce -apply-state (->> (take num-common key)
                                     (map (comp :orders #(get states %)))
                                     (reduce -process-orders context)))))
    ; If not a new state, simply execute the current state
    (-execute-state
      (->> key
           (map (comp :orders #(get states %)))
           (reduce -process-orders context))
      state)))

(defn -run-execution-loop
  [initial-state states context]
  (loop [visited-states #{initial-state}
          context         context]
    (let [current-state                  (:current-state context)
          context                        (-> context
                                             (-add-message :info (str "Executing state: " current-state))
                                             (-execute states (get states current-state)))
          previous-state                 current-state
          current-state                  (:current-state context)
          context                        (assoc context :previous-state previous-state)]
      (if (not= previous-state current-state)
        (if (contains? visited-states current-state)
          (-add-message context :warning (str "Loop detected: " initial-state " -> ... -> " previous-state " -> " current-state))
          (recur (conj visited-states current-state)
                 (assoc context :new-state? true)))
        context))))

(defn execute
    [{:keys [initial-data states-by-id]} {:keys [inputs config account exchange data orders]}]
  (let [static-data    {:static {:inputs   inputs
                                 :config   config
                                 :account  account
                                 :exchange exchange}}
        _ (println "STATIC" static-data)
        previous-state (:dawn/state data)
        data           (if previous-state data (-kv-evaluate static-data initial-data))
        current-state  (:dawn/state data)
        initial-state  (or previous-state current-state)
        context        (merge
                        static-data
                        {:libs           builtins/libraries
                         :orders         orders
                         :messages       []
                         :actions        []
                         :previous-state previous-state
                         :current-state  current-state
                         :new-state?     (not= previous-state current-state)
                         :data           (dissoc data :dawn/state)})
        results        (-run-execution-loop initial-state states-by-id context)]
    {:actions  (:actions results)
     :messages (:messages results)
     :data     (assoc (:data results)
                      :dawn/state (:current-state results)
                      :dawn/orders (set (keys (:orders results))))}))
