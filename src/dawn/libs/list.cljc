(ns dawn.libs.list)

(defn find
  [list value]
  (let [index (->> list
                   (map-indexed #(vector %1 %2))
                   (drop-while #(not= (second %) value))
                   (first)
                   (first))]
    (if (nil? index) -1 index)))

(defn zip
  [items-map]
  (if (empty? items-map)
    [] 
    (->> items-map
         vals
         (apply mapv vector)
         (map #(zipmap (keys items-map) %))
         vec)))
