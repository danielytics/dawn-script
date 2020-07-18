(ns dawn.evaluator
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.types :as types]))

(defn rearange-vararg-params [params expected-count]  
  (conj (vec (take (dec expected-count) params)) (subvec params (dec expected-count))))

(defn -call-function
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
  [a b]
  (when (= (type a)
           (type b))
    (cond
      (string? a) (str a b)
      (vector? a) (into a b)
      (map? a) (merge a b))))

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
   :bit-clear bit-clear
   :bit-set bit-set
   :bit-flip bit-flip
   :bit-test bit-test})

(defn -read-var
  [context node var-type var-name]
  (let [vars (get context (get {:static :static
                                :dynamic :data} var-type))
        value (get vars var-name ::not-found)]
    (if-not (and (keyword? value)
                 (= value ::not-found))
      value
      (throw+ {:error ::var
               :type :undefined
               :variable (name var-name)
               :variable-type var-type
               :metadata (meta node)
               :message (str "Could not read undefined variable '" (when (= var-type :dynamic) "#") (name var-name) "'")}))))

(defn evaluate
  [context [node-type & [value :as args] :as node]]
  (try+
    (case node-type
    ; Literals
      :integer value
      :float value
      :string value
      :boolean value
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
      :binary-op (let [lhs (evaluate context (second args))
                       rhs (evaluate context (second (next args)))]
                   (when (and (= value :/)
                              (zero? rhs))
                     (throw+ {:error ::arithmetic
                              :type :divide-by-zero
                              :metadata (meta node)
                              :message "Attempt to divide by zero"}))
                   ((get binary-operators value) lhs rhs))
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