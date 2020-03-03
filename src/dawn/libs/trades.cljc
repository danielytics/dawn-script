(ns dawn.libs.trades)


(defn max-contracts
  [context balance price]
  (let [leverage            (get-in context [:static :account :leverage])
        fee-rate            (*  0.00075 2)
        initial-margin-rate (+ 0.01 fee-rate) ; bitmex: 1% + taker entry fee + taker exit fee = 1.15%
        price-per-contract  (/ 1.0 price)
        contract-cost       (* (/ 1.0 leverage) price-per-contract)
        fee-cost            (* initial-margin-rate price-per-contract)
        cost-per-contract   (+ contract-cost fee-cost)
        max-contracts       (int (/ balance cost-per-contract))]
    max-contracts))

(defn price-offset
  [percent price]
  (+ price (* (/ percent 100.0) price)))