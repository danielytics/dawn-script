(ns dawn.core
  (:refer-clojure :exclude [load-string load-file])
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [try+]]
            [clojure.pprint]
            [dawn.utility :as util]
            [dawn.parser :as parser]
            [dawn.runtime :as runtime]
            [erinite.utility.xf :as xf]))

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

(defn load-string
  "Loads a TOML string to create a strategy ready for execution."
  [source]
  (parser/load-toml
   (parser/make-parser)
   source))

(defn load-file
  [file]
  (load-string (slurp file)))


;(debug :pprint (load-file "resources/strategy.toml"))
#_(debug :print-str (get-in
                     (load-file "resources/test_strategy.toml")
                     [:states "start" :trigger 0 :note :text]))

#_
(clojure.pprint/pprint
 (load-file "resources/testing-strategy.toml"))

(defn -set-event
  "Mutates the event object to include its trigger logic, if there is any"
  [strategy event]
  (-> event
      (assoc :trigger (when-let [trigger (get-in strategy [:triggers (:id event)])]
                        (update trigger :event #(str (get-in event [:order :tag]) "/" %))))))

(defn -generate-error-data
  "Generate detailed error structure from exception"
  [e]
  (let [start-index (get-in e [:metadata :instaparse.gll/start-index] 0)
        end-index (get-in e [:metadata :instaparse.gll/end-index] (count (:source e)))]
    {; Machine-readable error details
     :machine (if (instance? Exception e)
                e
                (assoc
                 (select-keys e [:type :variable :variable-type :function :operation :parameters :lib])
                 :variables (:variables e)
                 :ast (:ast e)
                 :what (:error e)
                 :path (:object-path e)))
     ; Human readable error
     :human {:message (:message e)
             :path  (string/join "." (map (xf/when keyword? name) (:object-path e)))
             :source (:source e)
             :index [start-index end-index]
             :highlight (str (string/join "" (repeat start-index " "))
                             (string/join "" (repeat (- end-index start-index) "^")))}}))

(defn -generate-error-log
  [error]
  (let [message (get-in error [:human :message])
        path    (get-in error [:human :path])
        details {:error-type "script"
                 :source (get-in error [:human :source])
                 :index (get-in error [:human :index])
                 :highlight (get-in error [:human :highlight])
                 :state (get-in error [:machine :path 1])
                 :item-index (get-in error [:machine :path 3])
                 :item-type (get-in error [:machine :path 2])
                 :attribute (get-in error [:machine :path 4])
                 :message message}]
    (util/make-message :error (str message " in " path) details)))

(defn execute
  "Execute an instance of a strategy. If (:data instance) is {}, a new instance is generated."
  [strategy instance input-data]
  (try+
   (let [input-data (update input-data :event #(-set-event strategy %))]
     {:type :result
      :result (runtime/execute strategy instance input-data)})
   (catch Object e
     (let [error (-generate-error-data e)]
       {:type :error
        :error error
        :result {:messages [(-generate-error-log error)]}}))))


(defn run-once
  "Debug tool, run the script once, printing the results and returning context ready to run againk"
  ([ctx] (run-once ctx {}))
  ([ctx changes]
   (let [{:keys [strategy continue? debug? instance input-data data event] :as ctx} (merge-with merge ctx changes)]
     (when continue?
       (println)
       (println "------------------------------------------------------------")
       (let [retval (execute strategy (assoc instance :debug? debug? :data data) (assoc input-data :event event))]
         (case (:type retval)
           :result (let [{:keys [orders messages data watch]} (:result retval)]
                     (println "Orders:")
                     (clojure.pprint/pprint orders)
                     (println "Watching:")
                     (clojure.pprint/pprint watch)
                     (println "Messages:")
                     (clojure.pprint/pprint messages)
                     (assoc ctx :data data))
           :error  (let [{:keys [human machine]} (:error retval)]
                     (println "Messages:")
                     (clojure.pprint/pprint (get-in retval [:result :messages]))
                     (println "ERROR:" (:message human))
                     (println "In:" (:path human))
                     (println)
                     (println (:source human))
                     (println (:highlight human))
                     (println)
                     (println "Additional Details:")
                     (clojure.pprint/pprint machine)
                     (println)
                     (assoc ctx continue? false))))))))

(defn -show-summary
  [{:keys [data]}]
  (println)
  (println "Final Data:")
  (clojure.pprint/pprint data))

#_
(clojure.pprint/pprint
 (load-file "resources/testing-strategy.toml"))

#_
(-> {:debug? false
     :strategy (load-file "resources/risk.toml")
     :continue? true
     :instance {:config {:risk-btc 0.5
                         :tp-risk 3
                         :fees 0.075}}
     :input-data {:inputs {:enter-long false
                           :enter-short false}
                  :market {:last-price 120}
                  :account {:balance  1000
                            :position 10
                            :avg-price 200
                            :leverage 1}}}
    (run-once)
    (run-once {:input-data {:inputs {:enter-long true}}
               :event {:stop-price 100}})
    (run-once {:event {:stop-price nil
                       :id :0.0/fill
                       :status :filled
                       :order {:tag "long"
                               :avg-fill-price 1000}}})
    (run-once {:input-data {:inputs {:enter-short true}}
               :event {:id nil
                       :status nil
                       :order nil
                       :stop-price 120}})
    (run-once {:event {:stop-price nil
                       :id :0.1/fill
                       :status :filled
                       :order {:tag "short"
                               :avg-fill-price 1000}}})
    (-show-summary))

