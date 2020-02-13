(ns dawn.core
  (:require [instaparse.core :as insta]
            [clojure.edn :as edn]
            [clojure.walk])
  (:import [org.tomlj Toml]))

(defn make-parser
  "Create an instaparse parser function from a grammar file"
  []
  (insta/parser (clojure.java.io/resource "grammar.ebnf")))

(defn -transform-binary-op
  "Transforms [left op-string right] into [:binary-op op-keyword left right]"
  [left operator right]
  [:binary-op (keyword operator) left right])

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
           :key-value-pair    (fn [k v] [k v])
           :map-literal       (fn [& kv-pairs] [:map-literal (into {} kv-pairs)])
           :identifier        keyword
           :static-lookup     (fn [v & path] [:static-lookup v (mapv #(if (vector? %) (second %) %) (vec path))])
           :unary-expression  (fn [op v] [:unary-op (keyword op) v])
           :binop-plusminus   -transform-binary-op
           :binop-muldiv      -transform-binary-op
           :binop-bitshift    -transform-binary-op
           :binop-bitwise     -transform-binary-op
           :binop-equality    -transform-binary-op
           :binop-in          -transform-binary-op
           :binop-logical-and -transform-binary-op
           :binop-logical-or  -transform-binary-op
           :binop-percent     -transform-binary-op
           :binop-pow         -transform-binary-op
           :call-expression   (fn [func-name & args] [:call func-name (vec args) {}])}))))

(defn -find-variables
  "Search the AST for variables by searching the tree for :dynamic-var and :static-var nodes"
  [ast]
  (->> ast
       ; Tree seq performs a depth-first search and returns a sequence of nodes
       (tree-seq #(or (vector? %)
                      (map? %))
                 identity)
       ; The nodes we're looking for are in format [type identifier], so we only keep vectors
       (filter vector?)
       ; Only keep the vectors where 'type' is one we're interested in
       (filter #(contains? #{:dynamic-var :static-var} (first %)))
       ; Group by 'type', returning a map where the keys are the type and values are a list of nodes
       (group-by first)))

(defn -capture-variables
  "Search the AST for variable references and return them in sets (separating static and dynamic variables)"
  [ast]
  (let [{:keys [static-var dynamic-var]} (-find-variables ast)]
    ; Take only the identifiers and convert each list into a set
    {:static (set (map second static-var))
     :dynamic (set (map second dynamic-var))}))

(declare ^:dynamic parse-errors)

(defmulti to-clj (fn [obj _ _] (type obj)))
(defmethod to-clj :default [x _ _] x)

(defmethod to-clj java.lang.String
  ;; A string, process it by applying the parser function
  [x path parser]
  (let [results (parse parser x)]
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
        {:vars (-capture-variables results)
         :ast  results}))))
 
(defmethod to-clj org.tomlj.Parser$1
 ;; Root of a TOML data-structure, acts like a map
  [x _ parser]
  (->> (.toMap x)
       (map (fn [[k v]] [(keyword k) (to-clj v [] parser)]))
       (into {})))

(defmethod to-clj org.tomlj.MutableTomlTable
 ;; A map data structure, convert each key into a keyword and recursively process the values
  [x path parser]
  (->> (.toMap x)
       (map (fn [[k v]]
              (let [k (keyword k)]
                [k (to-clj v (conj path k) parser)])))
       (into {})))

(defmethod to-clj org.tomlj.MutableTomlArray
 ;; A list data structure, recursively process each value
  [x path parser]
  (mapv
   (fn [idx v]
     (to-clj v (conj path idx) parser))
   (range)
   (.toList x)))


(defn load-toml
  "Take a parser function and source string and convert source string into a tree structure"
  [parser source]
  (binding [parse-errors (atom [])]
    (let [results (to-clj
                    (Toml/parse source)
                    [] parser)
          errors  @parse-errors]
      (if (seq errors)
        (do
          (println (count errors) "Parse Errors:")
          (doseq [[index error] (map-indexed vector errors)]
            (println "Error" (inc index) "at" (:path error))
            (println (:failure error)))
          results)
        results))))

(defn -process-state
  [state]
  state)

(defn prepare-states
  [{:keys [states]}]
  (->> (:state states)
       (group-by :id)
       (map (fn [[k state]] [k (-process-state (first state))]))
       (into {})))

(def binary-operators
  {; Arithmetic
   :+ + :- - :* * :/ /
   :percent #(* (/ %1 100) %2)
   :mod mod
   ; Boolean
   :and #(and %1 %2)
   :or #(or %1 %2)
   :xor #(and (or %1 %2) (not (and %1 %2)))
   ; Equality
   :== = :!= not=
   ; Comparison
   :> > :>= >= :< < :<= <=
   ; Bitwise
   :bit-and bit-and
   :bit-or bit-or
   :bit-xor bit-xor
   :bit-shl bit-shift-left
   :bit-shr bit-shift-right
   :bit-clear bit-clear
   :bit-set bit-set
   :bit-flip bit-flip
   :bit-test bit-test})

(defn evaluate
  [context [node-type & [value :as args] :as ast]]
  (case node-type
    ; Literals
    :integer value
    :float value
    :string value
    :boolean value
    :list-literal (mapv #(evaluate context %) args)
    :map-literal (into {} (for [[k v] value] [k (evaluate context v)]))
    ; Variable access
    :static-var (get (:inputs context) value)
    :dynamic-var (get (:data context) value)
    ; Field access
    :static-lookup (get-in (evaluate context value) (second args))
    :dynamic-lookup (get (evaluate context value) (evaluate context (second args)))
    ; Unary operators
    :unary-op (case value
                :not (not (evaluate context (second args)))
                :- (- (evaluate context (second args)))
                :bit-not (bit-not (evaluate context (second args))))
    ; Binary operators
    :binary-op (let [lhs (evaluate context (second args))
                     rhs (evaluate context (second (next args)))]
                 ((get binary-operators value) lhs rhs))))

#_
(clojure.pprint/pprint
 (get-in
  (prepare-states (load-toml (make-parser) (slurp "resources/strategy.toml")))
  ["not-in-position" :orders 0]))