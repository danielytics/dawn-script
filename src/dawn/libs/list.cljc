(ns dawn.libs.list
  (:require [clojure.set :as sets]
            [dawn.evaluator :as ev]))

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

(defn unzip
  "Take a vector of maps and return a map of vectors
   (comp zip unzip) and (comp unzip zip) act as identity
   (unzip [{:a 1 :b 4} {:a 2} {:a 3 :b 6}])
   => {:a [1 2 3] :b [4 nil 6]}"
  [items-list]
  (reduce
   (fn [accum item]
     (let [ks (sets/union (set (keys accum))
                          (set (keys item)))]
       (reduce #(update %1 %2 (fnil conj []) (get item %2)) accum ks)))
   {}
   items-list))

(defn get-element
  [items n]
  (if (>= n 0)
    (nth items n)
    (nth items (+ (count items) n))))

(defn shape
  "Returns the dimentions of a list, assumes every element of each nested list has the same number of elements
   (shape [1 2 3]) => [3]
   (shape [[1 2] [3 4] [5 6]]) => [3 2]
   (shape [[[1 2] [3 4]] [[5 6] [7 8]] [[9 10] [11 12]]]) => [3 2 2]"
  [items]
  (loop [sh []
         elem items]
    (if (vector? elem)
      (recur (conj sh (count elem))
             (first elem))
      sh)))

(defn transform-elems
  [context items func]
  (let [{:keys [args ast]} func
        var-name (first args)]
    (when-not (= (count args) 1)
      (throw (Exception. (str "List.transform called with function of `" (count args) "` arguments, expected 1"))))
    (mapv
     (fn [value]
       (ev/evaluate (assoc-in context [:static :args var-name] value) ast))
     items)))

(defn keep-elems
  [context items func]
  (let [{:keys [args ast]} func
        var-name (first args)]
    (when-not (= (count args) 1)
      (throw (Exception. (str "List.keep called with function of `" (count args) "` arguments, expected 1"))))
    (filterv
     (fn [value]
       (ev/evaluate (assoc-in context [:static :args var-name] value) ast))
     items)))

(defn remove-elems
  [context items func]
  (let [{:keys [args ast]} func
        var-name (first args)]
    (when-not (= (count args) 1)
      (throw (Exception. (str "List.remove called with function of `" (count args) "` arguments, expected 1"))))
    (vec
     (remove
      (fn [value]
        (ev/evaluate (assoc-in context [:static :args var-name] value) ast))
      items))))

(defn collect-elems
  [context items initial func]
  (let [{:keys [args ast]} func
        accum-var (first args)
        elem-var (second args)]
    (when-not (= (count args) 2)
      (throw (Exception. (str "List.collect called with function of `" (count args) "` arguments, expected 2"))))
    (reduce
     (fn [accum value]
       (-> context
           (assoc-in [:static :args] {accum-var accum
                                      elem-var value})
           (ev/evaluate ast)))
     initial
     items)))
