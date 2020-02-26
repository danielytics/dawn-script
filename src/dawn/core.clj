(ns dawn.core
  (:refer-clojure :exclude [load-string load-file])
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.parser :as parser]
            [dawn.runtime :as runtime]))

(defn -process-state
  [state]
  state)

(defn prepare-states
  [{:keys [states]}]
  (->> (:state states)
       (group-by :id)
       (map (fn [[k state]] [k (-process-state (first state))]))
       (into {})))

(defn find-libraries
  "By convention, variable names that start with an uppercase character are library names, unless the variable name is all-caps"
  [code]
  (->> code
       (:vars)
       (:static)
       (map name)
       (filter #(let [upper (string/upper-case %)]
                  (and (= (first %) (first upper))
                       (not= % upper))))
       (set)))

;(find-libraries {:vars {:static #{:foo :Bar :QUUX}}})

#_
(clojure.pprint/pprint
 (get-in
  (prepare-states (load-toml (make-parser) (slurp "resources/strategy.toml")))
  ["not-in-position" :orders 0]))

(defn load-string
  "Loads a TOML string to create a strategy ready for execution."
  [source]
  (parser/load-toml
    (parser/make-parser)
    source))

(defn load-file
  [file]
  (load-string (slurp file)))

(defn execute
  "Execute an instance of a strategy. If :data is {}, a new instance is generated."
  [strategy instance]
  (runtime/execute strategy instance))

(let [strategy (load-string (slurp "resources/strategy.toml"))
      instance {:inputs   (into
                           {:x 5
                            :status (into {} (map (fn [k] [(keyword k) true]) (get-in strategy [:inputs :status :fields])))}
                           (map (fn [k] [k 10]) (keys (dissoc (:inputs strategy) :status))))
                :account  {:balance  1000
                           :leverage 1}
                :config   {:order-sizes          [10 10 10 10]
                           :tp-trail-threshold   100
                           :trailing-stop-offset 10}
                :exchange {:candle    {:open   100
                                       :high   110
                                       :low    90
                                       :close  105
                                       :volume 1000}
                           :orderbook {:price  {:ask 110
                                                :bid 100}
                                       :volume {:ask 1000
                                                :bid 100}}}
                :orders   {}
                :data     {}}]
  (loop [instance instance
         counter 5]
    (println)
    (println "Executing...")
    (let [{:keys [actions messages data]} (execute strategy instance)]
      (println "Actions:")
      (clojure.pprint/pprint actions)
      (println "Messages:")
      (clojure.pprint/pprint messages)
      (if (pos? counter)
        (recur (assoc instance :data data) (dec counter))
        (do
          (println)
          (println "Final Data:")
          (clojure.pprint/pprint data))))))
