(ns dawn.libs.list)

(defn zip
  "Combine the values in a map into a vector of maps:
   (zip {:a [1 2 3] :b [4 5 6]})
   => [{:a 1 :b 4} {:a 2 :b 5} {:a 3 :b 6}]"
  [items-map]
  (if (empty? items-map)
    [] 
    (->> items-map
         vals
         (apply mapv vector)
         (mapv #(zipmap (keys items-map) %)))))
