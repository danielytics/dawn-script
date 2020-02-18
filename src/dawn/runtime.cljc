(ns dawn.runtime
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [dawn.builtins :as builtins]
            [dawn.evaluator :as evaluator]
            [dawn.types :as types]))

(defn -eval
  [context value]
  (if (types/formula? value)
    (evaluator/evaluate context (types/ast value))
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
  [context order]
  (when (-eval context (:when order))
    (->> (dissoc order :when)
         (map (fn [[k v]] [k (-eval context v)]))
         (into {}))))

(defn execute
  [{:keys [initial-data states]} {:keys [inputs config data]}]
  (let [data          (if (seq data)
                        data
                        initial-data)
        current-state (peek (:dawn/state data))
        context       {:static        {:inputs inputs
                                       :config config}
                       :libs          builtins/libraries
                       :orders        (:dawn/orders data)
                       :current-state current-state
                       :data          (dissoc data :dawn/state :dawn/orders)}
        state         (get states current-state)
        eval-kv       (-make-kv-evaluator context)
        orders        (remove
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
                         (:orders state)))
        triggers      (:triggers state)]
    (println "Orders: " orders)
    (println "Triggers: " triggers)))
