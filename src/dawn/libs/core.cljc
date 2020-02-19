(ns dawn.libs.core
  (:refer-clojure :exclude [apply])
  (:require [dawn.types :as types]))

(defn apply
  [context function arguments]
  (-> (:libs context)
      (get-in (types/path function))
      (:fn)
      (apply arguments)))

(defn doctext
  [context function]
  (-> (:libs context)
      (get-in (types/path function))
      (:doc)))