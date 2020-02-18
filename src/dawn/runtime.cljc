(ns dawn.runtime
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [dawn.builtins :as builtins]
            [dawn.evaluator :as evaluator]
            [dawn.types :as types]))

(defn -value
  [context [key value]]
  (vector
   key
   (if (types/formula? value)
     (do
       (println "Field:" key)
       (println "AST:" (types/ast value))
       (println "Vars:" (types/vars value))
       #_(evaluator/evaluate context (types/ast value)))
     value)))

(defn execute
  [{:keys [initial-data states]} {:keys [inputs config data]}]
  (let [data          (if (seq data)
                        data
                        initial-data)
        current-state (peek (:dawn/state data))
        context       {:inputs        inputs
                       :libs          builtins/libraries
                       :config        config
                       :orders        (:dawn/orders data)
                       :current-state current-state
                       :data          (dissoc data :dawn/state :dawn/orders)}
        state         (get states current-state)
        orders        (for [order (:orders state)]
                        (into {} (map  #(-value context %) order)))
        triggers      (:triggers state)]
    (println "Orders: " orders)
    (println "Triggers: " triggers)))
