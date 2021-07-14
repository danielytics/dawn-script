(ns dawn.libs.math)

(defn abs
  [x]
  (let [result (Math/abs x)]
    (println (str "Math.abs(" x ") => " result))
    result))

(defn floor
  [x]
  (Math/floor x))

(defn ceil
  [x]
  (Math/ceil x))
