(ns dawn.builtins-test
  (:require [clojure.test :refer :all]
            [dawn.builtins :as dawn]
            [dawn.types :as types]))

(deftest trades-test
  (testing "max-contracts"
    (is (= 988
           (dawn/trades-max-contracts {:strategy {:leverage 1}} 100 10))))
  
  (testing "price-offset"
    (is (= 950.0
           (dawn/trades-price-offset -5 1000)))))