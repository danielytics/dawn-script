(ns dawn.runtime
  (:require [slingshot.slingshot :refer [throw+ try+]]
            [dawn.builtins :as builtins]
            [dawn.evaluator :as evaluator]
            [dawn.types :as types]))

(defn -eval
  "Evaluate a value in a given context. If the value is a literal value, then it is returned
   unchanged. If the value is a formula, then the formula is evaluated with the given context."
  [context value]
  (if (types/formula? value)
    (try+
      (evaluator/evaluate
       (update context :static merge (:functions (types/vars value)))
       (types/ast value))
      (catch Object error
       (throw+ (assoc error
                      :object-path (types/keys value)
                      :source (types/source value)))))
    value))

(defn -generate-binding-combos
  "Generate a sequence of all combinations of bindings."
  [var-bindings]
  (reduce
    (fn [accum [k next]]
      (for [a accum
             b next]
        (assoc a k b)))
   [{}]
   var-bindings))

(defn -make-kv-evaluator
  "Return a function which will evaluate every value in the provided context for every value in a map."
  [context]
  (fn [[key value]]
    [key (-eval context value)]))

(defn -kv-evaluate
  "Take a map kv and a context and return a new map which is a copy of kv with all of the values evaluated if they are formulas."
  [context kv]
  (let [kv-eval (-make-kv-evaluator context)]
    (->> kv
         (map kv-eval)
         (into {}))))

(defn -evaluate-order
  "Evaluate the when expression and if true, evaluate each field of the order"
  [context order]
  (when (-eval context (get order :when true))
    (->> (dissoc order :when)
         (-kv-evaluate context))))

(defn -evaluate-orders
  "Generate a list of orders by evaluating the appropriate fields of orders and expanding foreach statemens"
  [context orders]
  (let [eval-kv (-make-kv-evaluator context)]
    (->> orders
         (reduce
          (fn [all-orders order-template]
            (if-let [foreach (:foreach order-template)]
              (into all-orders (for [var-bindings (-generate-binding-combos (map eval-kv foreach))]
                                 (-evaluate-order
                                  (update context :data merge var-bindings)
                                  (dissoc order-template :foreach))))
              (conj all-orders (-evaluate-order context order-template))))
          [])
         (remove nil?)
         (group-by :tag)
         ; If more than one order per tag, merge them into one
         (map (fn [[k v]] [k (reduce merge v)]))
         ; Return a tag->order map
         (into {}))))

(defn -check-trigger-fn
  "Return a function which checks if the 'when' parameter of a trigger evaluates to true."
  [context]
  (fn [{condition :when :as trigger}]
    (when (-eval context condition)
      (dissoc trigger :condition))))

(defn -add-message
  "Add a message to the message log."
  [context category message]
  (let [category-kw (keyword category)]
    (if (contains? #{:warning :error :info :note :order} category-kw)
      (update context :messages conj {:category category-kw
                                      :time     nil
                                      :text     message})
      (-add-message context :warning (str "Tried to add note with invalid category '" category "': " message)))))

(defn -eval-message
  "Add note to messaegs, if required"
  [context note]
  (if (seq note)
    (-add-message context
                  (get note :category :note)
                  (-eval context (get note :text "")))
    context))

(defn -process-trigger-action
  "Apply a triggers resulting actions to the context."
  [context trigger-action]
  (let [new-state (-eval context (:to-state trigger-action))]
    ; Need to retract orders here by creating actions
    (-> context
        (assoc :current-state (or new-state (:current-state context)))
        (update :data merge (-kv-evaluate context (:data trigger-action)))
        (-eval-message (:note trigger-action))
        (-eval-message (when new-state {:category :info
                                        :text (str "Transitioning state to: " new-state)})))))

(defn -process-orders
  "Generate a map of tag->orders and add it to the context."
  [context orders]
  (let [orders (-evaluate-orders context orders)]
    (update context :orders (partial merge-with merge) orders)))

(defn -evaluate-triggers
  "Test and apply triggers to the context."
  [context state]
  (let [check-trigger (-check-trigger-fn context)]
    (reduce
      (fn [context trigger]
        (if (check-trigger trigger)
          (-process-trigger-action context trigger)
          context))
      context
      (:trigger state))))

(defn -check-state-change
  "Check if a state transition has been triggered, abort reduction if so"
  [{:keys [current-state] :as context} previous-state]
  (if (not= current-state previous-state)
    (reduced context)
    context))

(defn -apply-state
  "Apply the actions of a single state to the context."
  [context state]
  (-> context
      (-eval-message (:note state))
      (update :data merge (-kv-evaluate context (:data state)))
      (-evaluate-triggers state)
      (-process-orders (:orders state))
      (-check-state-change (:current-state context))))

(defn -execution-pass
  "Perform one execution pass. If a new state has been entered, apply each parent state before applying the new state.
   If this is not a new state, then apply the current state.
   Applying a state consists of evaluating the orders and then evaluating the triggers. New states also setup data before this."
  [context states {:keys [key] :as state}]
  (if (:new-state? context)
    (let [num-common    (->> (get-in states [(:previous-state context) :key])
                             (map vector key)
                             (take-while #(apply = %))
                             (count))
          context       (-> context
                            (update :data select-keys (:variables state))
                            (assoc :orders {}))
          ; Get all of the orders for the in-common states
          common-orders (->> (take num-common key)
                             (map (comp :orders #(get states %)))
                             (reduce -process-orders context))]
      (->> key
           ; Find the number of common parent keys (if any) and drop them
           (drop num-common)
           ; Convert state ID's to state maps
           (map #(get states %))
           ; Apply each not-in-common parent state to context in turn
           ; The current state is always at the end of the key, so will be applied also
           (reduce -apply-state common-orders)))
    ; If not a new state, simply execute the current state
    (-evaluate-triggers
      (->> key
           (map (comp :orders #(get states %)))
           (reduce -process-orders context))
      state)))

(defn -run-execution-loop
  "Run the execution loop by applying the current state to the context and repeating this as long as the state has changed to a new state.
   Detects loops to ensure that it will return."
  [initial-state states context]
  (loop [visited-states #{initial-state}
         context         context]
    (let [current-state (:current-state context)
          context       (-> context
                            (-add-message :info (str "Executing state: " current-state))
                            (-execution-pass states (get states current-state))
                            (assoc :previous-state current-state))
          next-state    (:current-state context)]
      (if (not= next-state current-state)
        (if (contains? visited-states next-state)
          (-add-message context :warning (str "Loop detected: " initial-state " -> ... -> " current-state " -> " next-state))
          (recur (conj visited-states next-state)
                 (assoc context :new-state? true)))
        context))))

(defn execute
  "Construct a context map from a given strategy, data, configuration and inputs, then runs the execution loop against this context."
  [{:keys [initial-data states]} {:keys [inputs config account data event]}]
  (let [static-data    {:libs builtins/libraries
                        :static {:inputs   inputs
                                 :config   config
                                 :event    event
                                 :account  account}}
        previous-state (:dawn/state data)
        data           (if previous-state data (-kv-evaluate static-data initial-data))
        current-state  (:dawn/state data)
        initial-state  (or previous-state current-state)
        context        (merge
                        static-data
                        {:messages       []
                         :previous-state previous-state
                         :current-state  current-state
                         :new-state?     (not= previous-state current-state)
                         :data           (dissoc data :dawn/state)})
        context         (if-let [trigger (:trigger event)]
                          (-> context
                              (-add-message :info (str "Executing event handler" (when-let [tag (:event trigger)] (str ": " tag))))
                              (-process-trigger-action trigger))
                          context)
        results        (-run-execution-loop initial-state states context)]
    (-> results
        (select-keys [:messages :data :orders])
        (update :orders (comp vec vals))
        (assoc-in [:data :dawn/state] (:current-state results))
        (assoc-in [:data :dawn/orders] (set (keys (:orders results)))))))
