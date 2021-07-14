(ns dawn.evaluator
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.types :as types]
            [erinite.utility.xf :as xf]))

(defn -vars
  "Get a list of variables"
  [context]
  {:static (->> :time
                (dissoc (:static context))
                (map (juxt first (comp set keys second)))
                (into {}))
   :dynamic (dissoc (:data context) :dawn/orders)})

(defn rearange-vararg-params [params expected-count]  
  (conj (vec (take (dec expected-count) params)) (subvec params (dec expected-count))))

(defn -call-function
  "Call a function object, with given a collection of parameters
   Handles all cases: variadic functions, functions whose implementations require the context map, and error reporting"
  [context node func-obj parameters]
  (when (types/fn-ref? func-obj)    
    (when-let [func (get-in (:libs context) (types/path func-obj))]
      (let [expected-params (:params func)
            with-context? (= (first expected-params) :context)
            expected-count ((if with-context? dec identity) (count expected-params))
            params (if (= (last expected-params) :varargs) (rearange-vararg-params (vec parameters) expected-count) parameters)]
        (if (= (count params)
               expected-count)
          (if with-context?
            (apply (:fn func) context params)
            (apply (:fn func) params))
          (throw+ {:error ::call
                   :type :bad-arguments
                   :function (dissoc func :fn)
                   :parameters params
                   :lib (types/lib func-obj)
                   :variables (-vars context)
                   :metadata (meta node)
                   :message (str "Invalid number of arguments. Expected " (count (:params func)) " got " (count params))}))))))

(defn in?
  "true if coll contains elem"
  [elem coll]
  (boolean
    (if (or (map? coll)
            (set? coll))
      (contains? coll elem)
      (some #(= elem %) coll))))

(defn concatenate
  "Concatenate two values. Works for strings, vectors, sets and maps. Additionally strings can also append numbers and keywords, while both vectors and sets can append anything"
  [a b]
  (cond
    ; string ++ string|number
    (and (string? a)
         (or (string? b)
             (number? b))) (str a b)
    ; string ++ keyword
    (and (string? a)
         (keyword? b)) (str a (name b))
    ; vector ++ vector
    (and (vector? a)
         (vector? b)) (into a b)
    ; vector ++ anything
    (vector? a) (conj a b)
    ; set ++ set
    (and (set? a)
         (set? b)) (into a b)
    ; set ++ anything
    (set? a) (conj a b)
    ; map ++ map
    (and (map? a)
         (map? b)) (merge a b)
    ; Otherwise, unsupported pairs of types
    :else ::unsupported))

(def binary-operators
  {; Arithmetic
   :+ + :- - :* * :/ /
   :percent #(* (/ %1 100) %2)
   :mod mod
   :pow #(Math/pow %1 %2)
   ; Boolean
   :and #(and %1 %2)
   :or #(or %1 %2)
   :xor #(and (or %1 %2) (not (and %1 %2)))
   ; Equality
   :== = :!= not=
   ; Comparison
   :> > :>= >= :< < :<= <=
   ; Lists
   :in in?
   :++ concatenate
   ; Bitwise
   :bit-and bit-and
   :bit-or bit-or
   :bit-xor bit-xor
   :bit-shl bit-shift-left
   :bit-shr bit-shift-right
   :bit-flip bit-flip
   :bit-set bit-set
   :bit-clear bit-clear
   :bit-test bit-test})

(def non-nill-operators ; Operators whose arguments cannot be null
  #{:+ :- :* :/ :percent :mod :pow :> :>= :< :<= :bit-and :bit-or :bit-xor :bit-shl :bit-shr :bit-flip :bit-test :bit-clear :bit-set})

(defn -read-var
  "Read a variable, whether static or dynamic. Reports undefined variable if variable wasn't found in context"
  [context node var-type var-name]
  (let [vars (get context (get {:static :static
                                :dynamic :data} var-type))
        value (get vars var-name ::not-found)]
    (if-not (and (keyword? value)
                 (= value ::not-found))
      (if (decimal? value) (double value) value)
      (throw+ {:error ::var
               :type :undefined
               :variable (name var-name)
               :variable-type var-type
               :metadata (meta node)
               :variables (-vars context)
               :message (str "Could not read undefined variable '" (when (= var-type :dynamic) "#") (name var-name) "'")}))))

(defn -type-of
  [value]
  (cond
    (number? value) "number"
    (boolean? value) "boolean"
    (string? value) "text"
    (vector? value) "list"
    (decimal? value) "big-decimal"
    (map? value) "table"
    (nil? value) "nil"
    (set? value) "set"))

(defn evaluate
  "Evaluate an AST node within a context"
  [context [node-type & [value :as args] :as node]]
  (try+
    (case node-type
      ; Literals
      :nil nil
      :integer value
      :float value
      :string value
      :boolean value
      :function value
      :list-literal (mapv #(evaluate context %) args)
      :map-literal (into {} (for [[k v] value] [k (evaluate context v)]))
      ; Variable access
      :static-var (-read-var context node :static value)
      :dynamic-var (-read-var context node :dynamic value)
      ; Field access
      :static-lookup (get-in (evaluate context value) (second args))
      :dynamic-lookup (get (evaluate context value)
                           (let [key (evaluate context (second args))]
                             (if (string? key) (keyword key) key)))
      ; Unary operators
      :unary-op (case value
                  :not (not (evaluate context (second args)))
                  :- (- (evaluate context (second args)))
                  :bit-not (bit-not (evaluate context (second args))))
      ; Binary operators
      :binary-op (let [left (second args)
                       right (second (next args))
                       lhs (evaluate context left)
                       rhs (evaluate context right)]
                   (when (and (= value :/)
                              (zero? rhs))
                     (throw+ {:error ::arithmetic
                              :type :divide-by-zero
                              :operation [lhs (symbol value) rhs]
                              :metadata (meta node)
                              :variables (-vars context)
                              :message "Attempt to divide by zero"}))
                   (when (or (and (or (nil? lhs) (nil? rhs))
                                  (contains? non-nill-operators value))
                             (and (= value :in) (nil? rhs)))
                     (throw+ {:error ::arithmetic
                              :type :nil
                              :operation [lhs (symbol value) rhs]
                              :metadata (meta (if (nil? lhs) left right))
                              :variables (-vars context)
                              :message (str "'" ((xf/when keyword? name) value) "' could not be applied to nil")}))
                   (let [result ((get binary-operators value) lhs rhs)]
                     (if (= result ::unsupported)
                       (throw+ {:error ::arithmetic
                                :type :unsupported-types
                                :operation [lhs (symbol value) rhs]
                                :metadata (meta (if (nil? lhs) left right))
                                :variables (-vars context)
                                :message (str "Operation '" ((xf/when keyword? name) value) "' not supported for types '" (-type-of lhs) "' and '" (-type-of rhs) "'")})
                       result)))
      ; Ternary
      :ternary-expression (evaluate context (if (evaluate context value)
                                              (second args)
                                              (second (next args))))
      ; Function calls
      :call (let [func-obj   (evaluate context value)
                  parameters (map #(evaluate context %) (second args))]
              (-call-function context node func-obj parameters)))
(catch Exception e
  ; TODO: Send to datadog
  (println "Evaluation error in:" node-type args)
  (println "Metadata:" (meta node))
  (println "Local Data:" (:data context))
  (println "Exception:" e)
  (println))))

#_
(try+ ; TODO: This error reporting should be used somewhere where it can be reported to the user
  (-call-function {:libs {:foo {:bar {:name "bar"
                                      :fn (fn [a b] (* a b))
                                      :params [:a :b]}}}} (types/fn-ref :foo :bar) [])
  (catch [:error ::call :type :bad-arguments] {:keys [message function lib parameters]}
    (letfn [(fn->str [params] (str "[" (name lib) "." (:name function) ": " (string/join ", " params) "]"))]
      (println "Signature:" (fn->str (mapv name (:params function))))
      (println "Call:" (fn->str parameters)))))