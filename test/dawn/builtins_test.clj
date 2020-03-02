(ns dawn.builtins-test
  (:require [clojure.test :refer :all]
            [dawn.libs.trades :as trades]
            [dawn.libs.list :as list]))

(deftest trades-test
  (testing "max-contracts"
    (is (= 988
           (trades/max-contracts {:static {:account {:leverage 1}}} 100 10))))
  
  (testing "price-offset"
    (is (= 950.0
           (trades/price-offset -5 1000)))))

