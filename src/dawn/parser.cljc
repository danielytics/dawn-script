(ns dawn.parser
  (:require [instaparse.core :as insta]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.types :as types])
  (:import [org.tomlj Toml]))

(defn make-parser
  "Create an instaparse parser function from a grammar file"
  []
  (insta/parser (io/resource "grammar.ebnf")))

(defn -transform-binary-op
  "Transforms [left op-string right] into [:binary-op op-keyword left right]"
  [left operator right]
  [:binary-op (keyword operator) left right])

(defn -transform-fn-obj-body
  "Transform the body of a function object so that arguments are taken from args.* static lookup"
  [args ast]
  (insta/transform
   {:static-var (fn [var-name]
                  (if (contains? args var-name)
                    [:static-lookup [:static-var :args] [var-name]]
                    [:static-var var-name]))
    :call (fn [which func-args opts]
            [:call which (-transform-fn-obj-body args func-args) opts])}
   ast))

(defn parse
  "Takes an instaparse parser function and a source string and returns an AST"
  [parser source];
  (try
    (->> (parser source)
         (insta/add-line-and-column-info-to-metadata source)
         (insta/transform
          {:integer           (fn [& args] (->> args (apply str) edn/read-string int (vector :integer)))
           :float             (fn [& args] (->> args (apply str) edn/read-string double (vector :float)))
           :true              (constantly true)
           :false             (constantly false)
           :percent           (constantly :percent)
           :key-value-pair    (fn [k v] [k v])
           :map-literal       (fn [& kv-pairs] [:map-literal (into {} kv-pairs)])
           :identifier        keyword
           :static-lookup     (fn [v & path] [:static-lookup v (mapv #(if (vector? %) (second %) %) (vec path))])
           :function-var      (fn [func & fields]
                                (if (seq fields)
                                  [:static-lookup func (vec fields)]
                                  func))
           :function-literal  (fn [[_ & args] ast]
                                (println ast)
                                [:function {:args (vec args)
                                            :ast (-transform-fn-obj-body (set args) ast)}])
           :unary-expression  (fn [op v] [:unary-op (keyword op) v])
           :binop-plusminus   -transform-binary-op
           :binop-muldiv      -transform-binary-op
           :binop-bitshift    -transform-binary-op
           :binop-bitwise     -transform-binary-op
           :binop-equality    -transform-binary-op
           :binop-in          -transform-binary-op
           :binop-concat      -transform-binary-op
           :binop-logical-and -transform-binary-op
           :binop-logical-or  -transform-binary-op
           :binop-percent     -transform-binary-op
           :binop-pow         -transform-binary-op
           :binop-relational  -transform-binary-op
           :call-expression   (fn [func-name & args] [:call func-name (vec args) {}])}))))

(defn -find-variables
  "Search the AST for dynamic variables by searching the tree for :dynamic-var nodes"
  [ast]
  (->> ast
       ; Tree seq performs a depth-first search and returns a sequence of nodes
       (tree-seq #(or (vector? %)
                      (map? %))
                 identity)
       ; The nodes we're looking for are in format [:dynamic-var identifier], so we only keep vectors
       (filter #(and (vector? %)
                     (= (first %) :dynamic-var)))
       (map second)
       (set)))

(defn -find-statics
  "Search the AST for static variables by searcing the truu for :static-lookup nodes"
  [ast]
  (->> ast
       ; Tree seq performs a depth-first search and returns a sequence of nodes
       (tree-seq #(or (vector? %)
                      (map? %))
                 identity)
       ; The nodes we're looking for are in format [:static-lookuep [:static-var identifier] args], so we only keep vectors
       (filter #(and (vector? %)
                     (= (first %) :static-lookup)))
       (map (fn [[_ [node-type key] [sub-key & _]]]
              (when (= node-type :static-var)
                (when sub-key
                  {:category key
                   :field sub-key}))))
       (remove nil?)
       (set)))

(defn -get-identifiers
  [[node-type & identifiers]]
  (case node-type
    :static-var (vec identifiers)
    :static-lookup (into (vec (next (first identifiers)))
                         (second identifiers))))

(defn -find-functions
  "Search AST for function calls and extract the function identifiers"
  [ast]
  (->> ast
       (tree-seq #(or (vector? %)
                      (map? %))
                 identity)
       (filter vector?)
       (filter #(= (first %) :call))
       (map (comp -get-identifiers second))
       (set)))

(defn -make-functions-vars
  "Generate a function reference object for each function's identifiers (either just function name, or lib and function name)"
  [functions]
  (reduce
   (fn [funcs [lib key]]
     (let [[path func] (if (nil? key)
                         [[lib] (types/fn-ref :Core lib)]
                         [[lib key] (types/fn-ref lib key)])]
       (assoc-in funcs path func)))
   {}
   functions))

(defn -remove-functions
  "Remove functions from set of static vars.
   Function identifiers are parsed the same way as statics, so look the same. This removes the functions to leave only actual statics."
  [static-vars functions]
  (->> (for [[lib items] functions
             [func _] items]
         (keyword (name lib) (name func)))
       (apply disj static-vars)))

(defn -capture-variables
  "Search the AST for variable references and return them in sets (separating static and dynamic variables)"
  [ast]
  (let [static-vars (-find-statics ast)
        dynamic-vars (-find-variables ast)
        functions (-> ast
                      (-find-functions)
                      (-make-functions-vars))]
    {:static    (-remove-functions static-vars functions)
     :dynamic   dynamic-vars
     :functions functions}))

(defn -clean-path
  "Replace state.key with key"
  [path]
  (apply
   conj
   (reduce
    (fn [[path prev] key]
      (if (and (= prev :state)
               (string? key))
        [path key]
        [(conj path prev) key]))
    [[] (first path)]
    (rest path))))

(defn -transform-slashbang
  "Transform \".../#...\" into \"=> '.../' ++ #...]\""
  [text]
  (if-let [match (re-matches #"^(.*\/)(\#.*)$" text)]
    (apply format "=> '%s' ++ %s" (next match))
    text))

(declare ^:dynamic parse-errors)

(defmulti to-clj (fn [obj _ _] (type obj)))
(defmethod to-clj :default [x _ _] x)

; TODO: clojurescript/javascript version
(defmethod to-clj java.lang.String
  ;; A string, process it by applying the parser function
  [x path parser]
  (let [results (parse parser (-transform-slashbang x))
        path (-clean-path path)]
    (if (insta/failure? results)
      (let [error (insta/get-failure results)]
        (swap! parse-errors conj {:path    path
                                  :failure error})
        {:error error})
      ; If this string parses correctly, return the parsed value
      (let [results (second results)]
        (case (first results)
          ; Raw text (strings not starting with "=>") are returned as strings
          :raw-text (second results)
          ; Code strings arereturned as a data structure containing the variables accessed and the parsed abstract syntax tree
          (types/formula
           {:path path
            :source x
            :vars (-capture-variables results)
            :ast  results}))))))

(defmethod to-clj org.tomlj.Parser$1
 ;; Root of a TOML data-structure, acts like a map
  [x _ parser]
  (->> (.toMap x)
       (map (fn [[k v]] (let [key (keyword k)]
                          [key (to-clj v [key] parser)])))
       (into {})))

(defmethod to-clj org.tomlj.MutableTomlTable
 ;; A map data structure, convert each key into a keyword and recursively process the values
  [x path parser]
  (->> (.toMap x)
       (map (fn [[k v]]
              (let [key (keyword k)]
                [key (to-clj v (conj path key) parser)])))
       (into {})))

(defmethod to-clj org.tomlj.MutableTomlArray
 ;; A list data structure, recursively process each value
  [x path parser]
  (mapv
   (fn [idx v]
     (let [key (or (when (= (type v)
                            org.tomlj.MutableTomlTable)
                     (.getString v "id"))
                   idx)]
       (to-clj v (conj path key) parser)))
   (range)
   (.toList x)))

(defn -find-parent
  [states state-id]
  (get-in states [state-id :parent]))

(defn -preprocess-states
  "For each state, generate parent key list, extract variables and group states by id"
  [states initial-variables]
  (->> states
       (map (fn [[state-id state]]
              (let [parents (vec (into (list state-id) (take-while seq (iterate #(-find-parent states %) (:parent state)))))
                    variables (reduce set/union initial-variables (set (map #(keys (get-in states [% :data])) parents)))]
                [state-id (assoc state :key parents :variables variables)])))
       (into {})))

(defn -extract-order-triggers
  "Extract order status change triggers from list of orders for a given state"
  [orders state-id]
  (->> orders
       (map-indexed
        (fn [order-idx order]
          (let [[trig->id id->body] (reduce
                                     (fn [[ids bodies] [k body]]
                                       (let [event (name k)
                                             id (keyword (str state-id "." order-idx) event)
                                             body (assoc body :event event)]
                                         [(assoc ids k id) (assoc bodies id body)]))
                                     [{} {}]
                                     (:on order))]
            {:order    (assoc order :on trig->id)
             :triggers id->body})))
       (reduce
        (fn [results item]
          (-> results
              (update :orders conj (:order item))
              (update :triggers merge (:triggers item))))
       {:orders   []
        :triggers {}})))

(defn -get-statics
  "Get the static variable set from a value, if its a formula object"
  [value]
  (when (types/formula? value)
    (:static (types/vars value))))

(defn -extract-statics
  "Get a set of all statics found in a collection of orders and triggers"
  [entities]
  (reduce
   #(reduce set/union %1 (map (comp set -get-statics val) %2))
   #{}
   entities))

(defn -prepare-strategy
  "Takes a newly parsed strategy and processes it into the usable runtime form.
   This is done by:
     - Extracting order triggers from orders in each state
     - Extracting a list of static variables from every function object for each state
     - Transforming the collection of states into a map of states keyed by state id
     - Finding each states parents
     - Setting the initial state"
  [{:keys [inputs config data states]}]
  (let [raw-states        states
        [states triggers] (next
                           (reduce
                            (fn [[state-idx states triggers] state]
                              (let [results (-extract-order-triggers (:orders state) state-idx)
                                    orders (:orders results)]
                                [(inc state-idx)
                                 (conj states (assoc state
                                                     :watch (-extract-statics (concat orders (:trigger state)))
                                                     :orders orders))
                                 (merge triggers (:triggers results))]))
                            [0 [] {}]
                            (:state states)))]
    (-> {:inputs       (or inputs {})
         :config       (or config [])
         :initial-data (or data {})
         :triggers     triggers
         :states       (->> states
                            (group-by :id)
                            (map (fn [[k v]] [k (first v)]))
                            (into {}))}
        (assoc-in [:initial-data :dawn/state] (:initial raw-states))
        (update :states -preprocess-states (set (keys data))))))

(defn -extract-errors
  [obj]
  (doseq [error (.errors obj)]
    (let [position (.position error)]
      (swap! parse-errors conj {:line (.line position)
                                :column (.column position)
                                :failure (.toString error)}))))

(defn load-toml
  "Take a parser function and source string and convert source string into a tree structure"
  [parser source]
  (binding [parse-errors (atom [])]
    (let [toml-obj (Toml/parse source) ; TODO: Add clojurescript/javascript support
          results (if (.hasErrors toml-obj)
                    (-extract-errors toml-obj)
                    (to-clj toml-obj [] parser))
          errors  @parse-errors]
      (if (seq errors)
        #_(do
          (println (count errors) "Parse Errors:")
          (doseq [[index error] (map-indexed vector errors)]
            (println "Error" (inc index) "at" (or (:path error) (str (:line error) ":" (:column error))))
            (println (:failure error))))
        {:errors errors
         :data results}
        (-prepare-strategy results)))))
