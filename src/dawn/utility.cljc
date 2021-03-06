(ns dawn.utility
  (:require [tick.alpha.api :as t]))

(defn timestamp
  []
  (t/long (t/now)))

(defn make-message
  "Create a new message map at current time"
  ([category message]
   (make-message category message nil))
  ([category message details]
   (cond-> {:category category
            :time (t/now)
            :text (or message "")}
     details (assoc :details details))))

(defn add-message
  "Adds a new message at current time to the log"
  [context category message]
  (let [category-kw (keyword category)]
    (update context :messages conj 
            (if (contains? #{:warning :error :info :note :order} category-kw)
              (make-message category message)
              (make-message :warning (str "Tried to add note with invalid category '" category "': " message))))))