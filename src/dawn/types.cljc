(ns dawn.types)

(defprotocol Formula
  (vars [_] "Return the variables used in the formula")
  (ast [_] "Return the formula's abstract syntax tree"))

(defprotocol FuncRef
  (path [_] "Gets the path to the function object")
  (lib [_] "Gets the library in which the funciton object resides"))

(deftype FormulaObj [vars ast]
  Formula
  (vars [_] vars)
  (ast [_] ast))

(deftype FunctionVar [path]
  FuncRef
  (path [_] path)
  (lib [_] (first path)))

(defn formula
  [{:keys [vars ast]}]
  (->FormulaObj vars ast))

(defn formula?
  [v]
  (satisfies? Formula v))

(defn fn-ref
  [lib key]
  (->FunctionVar [lib key]))

(defn fn-ref?
  [v]
  (satisfies? FuncRef v))
