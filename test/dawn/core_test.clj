(ns dawn.core-test
  (:require [clojure.test :refer :all]
            [dawn.core :as dawn]
            [dawn.types :as types]))

(deftest analysis-test
  (testing "capture of variables in expressions"
    (is (= {:static #{:foo :abc}
            :dynamic #{:a :test}}
           (dawn/-capture-variables
             [:call [:static-lookup [:static-var :foo] [:bar]]
              [[:binary-op :+ [:integer 1] [:static-lookup [:map-literal {:x [:dynamic-var :test]}] [:x]]]
               [:call [:static-var :abc] [[:dynamic-var :a]] {}]] {}])))))


(deftest evaluation-test
  ;; Test literals
  (doseq [[ast expected] {; Basic types
                          [:integer 5]                                      5
                          [:float 1.2]                                      1.2
                          [:string "hi"]                                    "hi"
                          [:boolean true]                                   true
                            ; Composite types
                          [:list-literal [:integer 1] [:integer 2]]         [1 2]
                          [:map-literal {:a [:integer 1]
                                         :b [:integer 2]}] {:a 1
                                                            :b 2}}]
    (testing (str "evaluate literal: " (first ast))
      (is (= expected (dawn/evaluate {} ast)))))

  ;; Test variables and field access
  (doseq [[ast expected] {[:static-var :a]                                            10
                          [:dynamic-var :b]                                           20
                          [:static-lookup [:list-literal [:integer 1]] [0]]           1
                          [:static-lookup [:map-literal {:a [:integer 2]}] [:a]]      2
                          [:static-lookup [:static-var :c] [:x]]                      5
                          [:static-lookup [:dynamic-var :d] [1]]                      7
                          [:dynamic-lookup [:list-literal [:integer 3]] [:integer 0]] 3}]
    (testing (str "evaluate variable access: " ast)
      (is (= expected
             (dawn/evaluate {:inputs {:a 10
                                      :c {:x 5}}
                             :data   {:b 20
                                      :d [6 7]}} ast)))))
  
  ; Test unary expressions
  (testing "unary expressions"
    (is (= true (dawn/evaluate {} [:unary-op :not [:boolean false]])))
    (is (= false (dawn/evaluate {} [:unary-op :not [:boolean true]])))
    (is (= -2 (dawn/evaluate {} [:unary-op :- [:integer 2]])))
    (is (= 3.2 (dawn/evaluate {} [:unary-op :- [:float -3.2]]))))
  
  ; Test binary expressions
  (doseq [[ast expected] {; or
                          [:binary-op :or [:boolean false] [:boolean true]] true
                          [:binary-op :or [:boolean true] [:boolean false]] true
                          [:binary-op :or [:boolean false] [:boolean false]] false
                          ; xor                          
                          [:binary-op :xor [:boolean false] [:boolean true]] true
                          [:binary-op :xor [:boolean true] [:boolean false]] true
                          [:binary-op :xor [:boolean false] [:boolean false]] false
                          [:binary-op :xor [:boolean true] [:boolean true]] false
                          ; and
                          [:binary-op :and [:boolean false] [:boolean false]] false
                          [:binary-op :and [:boolean true] [:boolean false]] false
                          [:binary-op :and [:boolean false] [:boolean true]] false
                          [:binary-op :and [:boolean true] [:boolean true]] true
                          ; equality
                          [:binary-op :== [:integer 1] [:integer 2]] false
                          [:binary-op :== [:string 1] [:integer 2]] false
                          [:binary-op :!= [:integer 1] [:integer 2]] true
                          [:binary-op :!= [:string 1] [:integer 2]] true
                          ; relational
                          [:binary-op :> [:integer 1] [:integer 2]] false
                          [:binary-op :> [:integer 1] [:integer 1]] false
                          [:binary-op :> [:integer 2] [:integer 1]] true
                          [:binary-op :>= [:integer 1] [:integer 2]] false
                          [:binary-op :>= [:integer 1] [:integer 1]] true
                          [:binary-op :>= [:integer 2] [:integer 1]] true
                          [:binary-op :< [:integer 2] [:integer 1]] false
                          [:binary-op :< [:integer 2] [:integer 2]] false
                          [:binary-op :< [:integer 1] [:integer 2]] true
                          [:binary-op :<= [:integer 2] [:integer 1]] false
                          [:binary-op :<= [:integer 2] [:integer 2]] true
                          [:binary-op :<= [:integer 1] [:integer 2]] true
                          ; in ???
                          ;[:binary-op :in [:integer 1] [:list-literal 1 2 3]] true
                          ; bitwise
                          [:binary-op :bit-and [:integer 12] [:integer 25]] 8
                          [:binary-op :bit-or [:integer 12] [:integer 25]] 29
                          [:binary-op :bit-xor [:integer 12] [:integer 25]] 21
                          [:binary-op :bit-test [:integer 4] [:integer 2]] true
                          [:binary-op :bit-test [:integer 4] [:integer 1]] false
                          [:binary-op :bit-set [:integer 4] [:integer 1]] 6
                          [:binary-op :bit-clear [:integer 6] [:integer 1]] 4
                          [:binary-op :bit-flip [:integer 6] [:integer 1]] 4
                          [:binary-op :bit-flip [:integer 4] [:integer 1]] 6
                          ; bit-shift 
                          [:binary-op :bit-shl [:integer 4] [:integer 1]] 8
                          [:binary-op :bit-shr [:integer 16] [:integer 2]] 4
                          [:binary-op :bit-shl [:integer 16] [:integer 0]] 16
                          [:binary-op :bit-shr [:integer 16] [:integer 0]] 16
                          ; plusminus
                          [:binary-op :+ [:integer 2] [:integer 3]] 5
                          [:binary-op :- [:integer 2] [:integer 3]] -1
                          ; muldev
                          [:binary-op :* [:integer 2] [:integer 3]] 6
                          [:binary-op :/ [:integer 6] [:integer 3]] 2                          
                          [:binary-op :mod [:integer 7] [:integer 3]] 1
                          ; pow ??
                          ;[:binary-op :pow [:integer 2] [:integer 4]] 16
                          ; percent
                          [:binary-op :percent [:integer 10] [:integer 50]] 5}]
    (testing (str "binary expression: " (second ast))
      (is (= expected (dawn/evaluate {} ast)))))
  
  
  ; Test function calls.
  (let [context {:libs {:core {:add-2 {:params  [:integer]
                                       :returns :integer
                                       :name    "add-2"
                                       :doc     "Adds two to input"
                                       :fn      #(+ % 2)}}}
                 :inputs {:add-2 (types/fn-ref :core :add-2)}}]
    (doseq [[ast expected] {[:call [:static-var :add-2] [[:integer 10]] {}] 12}]
      (testing (str "function call: " (second (second ast)))
        (is (= expected (dawn/evaluate context ast))))))
  )