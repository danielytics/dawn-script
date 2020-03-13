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

#_(clojure.pprint/pprint
 (:config (load-file "resources/strategy.toml")))

(defn execute
  "Execute an instance of a strategy. If :data is {}, a new instance is generated."
  [strategy instance]
  (runtime/execute strategy instance))

(defn friendly-path
  [strategy path]
  (->> path         
       (reduce (fn [[path object] key]
                 (let [object (get object key)
                       key    (if (number? key)
                                (if (contains? object :id)
                                  (:id object)
                                  key)
                                (name key))]
                   [(conj path key) object]))
               [[] strategy])
       (first)
       (string/join ".")))

#_(into
   {:x      15
    :status (into {} (map (fn [k] [(keyword k) true]) (get-in strategy [:inputs :status :fields])))}
   (map (fn [k] [k 10]) (keys (dissoc (:inputs strategy) :status))))

(let [strategy (load-file "resources/test_strategy.toml")
      instance {:inputs   {:in1 0
                           :in2 0}
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
  (try+
   (loop [instance instance
          counter  2]
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
           (clojure.pprint/pprint data)))))
   (catch Object e 
     (println "ERROR:" e)
     (println (:message e))
     (println "In:" (friendly-path strategy (:object-path e)))
     (println (:source e))
     (print (string/join "" (repeat (get-in e [:metadata :instaparse.gll/start-index]) " ")))
     (println (string/join "" (repeat (- (get-in e [:metadata :instaparse.gll/end-index])
                                         (get-in e [:metadata :instaparse.gll/start-index])) "^"))))))
