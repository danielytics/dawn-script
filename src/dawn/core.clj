(ns dawn.core
  (:refer-clojure :exclude [load-string load-file])
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.pprint :refer [pprint]]
            [com.walmartlabs.datascope :as scope]
            [rhizome.viz :as viz]
            [dawn.parser :as parser]
            [dawn.runtime :as runtime]
            [erinite.utility.xf :as xf]))

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

(defn load-string
  "Loads a TOML string to create a strategy ready for execution."
  [source]
  (parser/load-toml
    (parser/make-parser)
    source))

(defn load-file
  [file]
  (load-string (slurp file)))

(defn debug
  ([data] (debug :pprint data))
  ([how data]
   (case how
     :pprint (clojure.pprint/pprint data)
     :print (println data)
     :print-str (println (str data))
     :str (str data)
     :view (scope/view data)
     :image (-> data scope/dot viz/dot->image (viz/save-image "debug.png")))))

;(debug :pprint (load-file "resources/strategy.toml"))
#_
(debug :print-str (get-in
                   (load-file "resources/test_strategy.toml")
                   [:states "start" :trigger 0 :note :text]))

#_(clojure.pprint/pprint
 (:config (load-file "resources/strategy.toml")))

(defn execute
  "Execute an instance of a strategy. If :data is {}, a new instance is generated."
  [strategy instance]
  (try+
   (let [instance (update instance :event (fn [event]
                                             (-> event
                                                 (select-keys [:status :order])
                                                 (assoc :trigger (-> strategy
                                                                     (get-in [:triggers (:id event)])
                                                                     (update :event #(str (get-in event [:order :tag]) "/" %)))))))
         {:keys [orders messages data]} (runtime/execute strategy instance)]
     {:type :result
      :result {:orders orders
               :messages messages
               :data data}})
   (catch Object e
     (let [start-index (get-in e [:metadata :instaparse.gll/start-index] 0)
           end-index (get-in e [:metadata :instaparse.gll/end-index] (count (:source e)))]
       {:type :error
        :error {:message (:message e)
                :details (if (instance? Exception e)
                           e
                           (assoc
                            (select-keys e [:type :variable :variable-type :function :parameters :lib])
                            :what (:error e)
                            :path (:object-path e)))
                :path  (string/join "." (map (xf/when keyword? name) (:object-path e)))
                :source {:raw (:source e)
                         :index [start-index end-index]
                         :highlight (str (:source e) "\n"
                                         (string/join "" (repeat start-index " "))
                                         (string/join "" (repeat (- end-index start-index) "^")))}}}))))

(let [strategy (load-file "resources/test_strategy.toml")
      instance {:inputs {:in1 0
                         :in2 0}
                :config {:order-sizes          [10 10 10 10]
                         :tp-trail-threshold   100
                         :trailing-stop-offset 10}
                :account {:balance  1000
                          :leverage 1}}]
  (loop [data {}
         counter 2
         event {:id :1.0/fill
                :status :filled
                :order {:tag "order"}}]
    (let [retval (execute strategy (assoc instance :data data :event event))]
      (case (:type retval)
        :result (let [{:keys [orders messages data] :as foo} (:result retval)]
                  (println "---")
                  (println "Orders:")
                  (pprint orders)
                  (println "Messages:")
                  (pprint messages)
                  (if (pos? counter)
                    (recur data (dec counter) nil)
                    (do
                      (println)
                      (println "Final Data:")
                      (pprint data))))
        :error  (let [{:keys [details message source path] :as error} (:error retval)]
                  (debug :pprint error)
                  (println "ERROR:" details)
                  (println message)
                  (println "In:" path)
                  (println (:highlight source)))))))
