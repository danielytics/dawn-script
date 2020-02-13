(ns dawn.core-test
  (:require [clojure.test :refer :all]
            [dawn.core :as dawn]))

(deftest grammar-test
  (let [parser (dawn/make-parser)]
    (testing "raw text should be untransformed"
      (is (= [:dawn [:raw-text "raw text"]]
             (parser "raw text"))))
    
    (doseq [[source expected]
            {; Literals
             "=> 1"                           [:integer 1]
             "=> -1"                          [:integer -1]
             "=> 1.1"                         [:float 1.1]
             "=> 'hello'"                     [:string "hello"]
             "=> true"                        [:boolean true]
             "=> false"                       [:boolean false]
             "=> [1, 2, 3]"                   [:list-literal [:integer 1] [:integer 2] [:integer 3]]
             "=> {a: 1, b: 2}"                [:map-literal {:a [:integer 1]
                                                             :b [:integer 2]}]
             ; Variables
             "=> abc"                         [:static-var :abc]
             "=> #abc"                        [:dynamic-var :abc]
             ; Field lookup
             "=> a.b"                         [:static-lookup [:static-var :a] [:b]]
             "=> a.1"                         [:static-lookup [:static-var :a] [1]]
             "=> a.1.b"                       [:static-lookup [:static-var :a] [1 :b]]
             "=> [1, 2].0"                    [:static-lookup [:list-literal [:integer 1] [:integer 2]] [0]]
             "=> {a: 1}.a"                    [:static-lookup [:map-literal {:a [:integer 1]}] [:a]]
             "=> a.(b)"                       [:dynamic-lookup [:static-var :a] [:static-var :b]]
             "=> a.[foo:]"                    [:dynamic-lookup [:static-var :a] [:call [:static-var :foo] [] {}]]
             "=> a.#b"                        [:dynamic-lookup [:static-var :a] [:dynamic-var :b]]
             "=> #a.b"                        [:static-lookup [:dynamic-var :a] [:b]]
             "=> a.#b.c"                      [:static-lookup [:dynamic-lookup [:static-var :a] [:dynamic-var :b]] [:c]]
            ; Unary operators
             "=> not true"                    [:unary-op :not [:boolean true]]
             "=> -(a)"                        [:unary-op :- [:static-var :a]]
             ; Expressions
             "=> 1 + 2"                       [:binary-op :+ [:integer 1] [:integer 2]]
             "=> 1 + 2 * 3"                   [:binary-op :+ [:integer 1] [:binary-op :* [:integer 2] [:integer 3]]]
             "=> 1 * 2 + 3"                   [:binary-op :+ [:binary-op :* [:integer 1] [:integer 2]] [:integer 3]]
             "=> (1 + 2) * 3"                 [:binary-op :* [:binary-op :+ [:integer 1] [:integer 2]] [:integer 3]]
             "=> 1 in [1, 2, 3]"              [:binary-op :in [:integer 1] [:list-literal [:integer 1] [:integer 2] [:integer 3]]]                                                                ; Function calls
             "=> [foo:]"                      [:call [:static-var :foo] [] {}]
             "=> [foo: 1, 2]"                 [:call [:static-var :foo] [[:integer 1] [:integer 2]] {}]
             "=> [foo.bar: 1 + 2, [abc: #a]]" [:call [:static-lookup [:static-var :foo] [:bar]] [[:binary-op :+ [:integer 1] [:integer 2]] [:call [:static-var :abc] [[:dynamic-var :a]] {}]] {}]}]

      (testing (str "expression: " source)
        (is (=  [:dawn expected]
                (dawn/parse parser source)))))
    
    (testing "precedence matches bracketed expressions"
      (let [a "=> 1 + 2 * 3 - 4 and 5 + 2 * 3 ^ -2"
            b "=> ((1 + (2 * 3)) - 4) and (5 + (2 * (3 ^ (-2))))"]
        (is (= (dawn/parse parser a)
               (dawn/parse parser b)))))))

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
  (doseq [[ast expected] {[:binary-op :+ [:integer 2] [:integer 3]] 5}]
    (testing (str "binary expression: " (second ast))
      (is (= expected (dawn/evaluate {} ast)))))
  )