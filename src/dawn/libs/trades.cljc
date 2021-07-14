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


(defn max-contracts-NEXTGEN
  [context balance price]
  (let [leverage                    (get-in context [:static :account :leverage])
        fee-rate                    (get-in context [:static :market :fees :taker])
        initial-margin-rate         (get-in context [:static :market :fees :initial-margin])
        fee-rate-per-contract       (+ fee-rate fee-rate initial-margin-rate) ; fee-rate for entry and again for exit
        price-per-contract          (/ 1.0 price)
        max-without-fees            (int (/ (* balance leverage) price-per-contract))
        fees-for-max                (* max-without-fees * fee-rate-per-contract)
        leveraged-balacne-less-fees (* (- balance fees-for-max) * leverage)
        max-after-fees              (int (/ leveraged-balacne-less-fees price-per-contract))]
    max-after-fees))


(defn price-offset
  [percent price]
  (+ price (* (/ percent 100.0) price)))

(defn risk-based-contracts
  [balance price stop-price percentage-loss]
  (/  (* balance price percentage-loss)
      (/ stop-price price)))

(defn position-side
  [context value]
  (let [position (get-in context [:static :account :position])]
    (cond
      (pos? position) value
      (neg? position) (- value)
      :else 0)))

(defn distribute
  [total number]
  (let [split (int (/ total number))
        distribution (vec (repeat number split))]
    (if (not= (* split number) total)
      (update distribution 0 #(+ % (- total (* split number))))
      distribution)))
