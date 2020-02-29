(ns dawn.types)

(defprotocol Formula
  (source [_] "Returns the source string for the formula")
  (keys [_] "Returns the keys into the object tree to reach this formula")
  (vars [_] "Return the variables used in the formula")
  (ast [_] "Return the formula's abstract syntax tree"))

(defprotocol FuncRef
  (path [_] "Gets the path to the function object")
  (lib [_] "Gets the library in which the funciton object resides"))

(deftype FormulaObj [object-path source-code vars ast]
  Formula
  (keys [_] object-path)
  (source [_] source-code)
  (vars [_] vars)
  (ast [_] ast)
    ; Make equality work for tests
  Object
  (toString [_] source-code)
  (hashCode [_] (.hashCode object-path))
  (equals [_ other]
    (= object-path (keys other))))

(deftype FunctionVar [fn-path]
  FuncRef
  (path [_] fn-path)
  (lib [_] (first fn-path))
  ; Make equality work for tests
  Object
  (toString [_] (str "[FunctionVar: " fn-path "]"))
  (hashCode [fn-path] (.hashCode fn-path))
  (equals [_ other]
    (and (satisfies? FunctionVar other)
         (= fn-path (path other)))))

(defn formula
  [{:keys [path vars source ast]}]
  (->FormulaObj path source vars ast))

(defn formula?
  [v]
  (satisfies? Formula v))

(defn fn-ref
  [lib key]
  (->FunctionVar [lib key]))

(defn fn-ref?
  [v]
  (satisfies? FuncRef v))