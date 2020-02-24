(ns dawn.libs.list)

(defn find
  [list value]
  (let [index (first (first (drop-while #(not= (second %) value) (map-indexed #(vector %1 %2) list))))]
    (if (= index nil) -1 index)))