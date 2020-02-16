(ns dawn.core
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.types :as types]
            [dawn.builtins :as builtins])
  (:import [org.tomlj Toml]))

(defn -process-state
  [state]
  state)

(defn prepare-states
  [{:keys [states]}]
  (->> (:state states)
       (group-by :id)
       (map (fn [[k state]] [k (-process-state (first state))]))
       (into {})))

(defn -call-function
  [context func-obj parameters]
  (when (types/fn-ref? func-obj)
    (when-let [func (get-in (:libs context) (types/path func-obj))]
      (if (= (count parameters)
             (count (:params func)))
        (apply (:fn func) parameters)
        (throw+ {:error ::call
                 :type :bad-arguments
                 :function (dissoc func :fn)
                 :parameters parameters
                 :lib (types/lib func-obj)
                 :message (str "Invalid number of arguments. Expected " (count (:params func)) " got " (count parameters))})))))


#_
(try+ ; TODO: This error reporting should be used somewhere where it can be reported to the user
  (-call-function {:libs {:foo {:bar {:name "bar"
                                      :fn (fn [a b] (* a b))
                                      :params [:a :b]}}}} (fn-ref :foo :bar) [])
  (catch [:error ::call :type :bad-arguments] {:keys [message function lib parameters]}
    (letfn [(fn->str [params] (str "[" (name lib) "." (:name function) ": " (string/join ", " params) "]"))]
      (println "Signature:" (fn->str (mapv name (:params function))))
      (println "Call:" (fn->str parameters)))))

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

(defn in?
  "true if coll contains elem"
  [elem coll]
  (boolean
    (if (or (map? coll)
            (set? coll))
      (contains? coll elem)
      (some #(= elem %) coll))))

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
   ; In
   :in in?
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
  [context [node-type & [value :as args]]]
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
                 ((get binary-operators value) lhs rhs))
    ; Function calls
    :call (let [func-obj (evaluate context value)
                parameters (map #(evaluate context %) (second args))]
            (-call-function context func-obj parameters))))

#_
(clojure.pprint/pprint
 (get-in
  (prepare-states (load-toml (make-parser) (slurp "resources/strategy.toml")))
  ["not-in-position" :orders 0]))
