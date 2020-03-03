(ns dawn.builtins-test
  (:require [clojure.test :refer :all]
            [dawn.libs.trades :as trades]
            [dawn.libs.list :as list]))

(deftest trades-test
  (testing "max-contracts"
    (is (= 988
           (trades/max-contracts {:strategy {:leverage 1}} 100 10))))
  
  (testing "price-offset"
    (is (= 950.0
           (trades/price-offset -5 1000)))))

(deftest list-test
  (testing "find"
    (is (= 0 (list/find [:a :b :c :d] :a)))
    (is (= 3 (list/find [:a :b :c :d] :d)))
    (is (= 1 (list/find [:a :b :c :d] :b)))
    (is (= -1 (list/find [:a :b :c :d] :x)))))

(deftest list-zip
  (testing "zip"
    (is (= [] (list/zip {})))
    (is (= [{:a 1 :b 4} {:a 2 :b 5} {:a 3 :b 6}] (list/zip {:a [1 2 3] :b [4 5 6]})))
    (is (= [{:a 1, :b 5} {:a 2, :b 6} {:a 3, :b 7} {:a 4, :b 8}] (list/zip {:a [1 2 3 4] :b [5 6 7 8]})))))
