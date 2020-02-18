(ns dawn.parser-test
  (:require [clojure.test :refer :all]
            [dawn.parser :as dawn]
            [instaparse.core :as insta]))


(deftest analysis-test
  (testing "capture of variables in expressions"
    (is (= {:static  #{:foo :abc}
            :dynamic #{:a :test}}
           (dawn/-capture-variables
            [:call [:static-lookup [:static-var :foo] [:bar]]
             [[:binary-op :+ [:integer 1] [:static-lookup [:map-literal {:x [:dynamic-var :test]}] [:x]]]
              [:call [:static-var :abc] [[:dynamic-var :a]] {}]] {}])))))


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
             "=> 1 - 2"                       [:binary-op :- [:integer 1] [:integer 2]]
             "=> 25% of 100"                  [:binary-op :percent [:integer 25] [:integer 100]]
             "=> 1 + 2 * 3"                   [:binary-op :+ [:integer 1] [:binary-op :* [:integer 2] [:integer 3]]]
             "=> 1 * 2 + 3"                   [:binary-op :+ [:binary-op :* [:integer 1] [:integer 2]] [:integer 3]]
             "=> (1 + 2) * 3"                 [:binary-op :* [:binary-op :+ [:integer 1] [:integer 2]] [:integer 3]]
             "=> 1 in [1, 2, 3]"              [:binary-op :in [:integer 1] [:list-literal [:integer 1] [:integer 2] [:integer 3]]]
             ; Function calls
             "=> [foo:]"                      [:call [:static-var :foo] [] {}]
             "=> [foo: 1, 2]"                 [:call [:static-var :foo] [[:integer 1] [:integer 2]] {}]
             "=> [foo.a.b.c: 1, 2]"           [:call [:static-lookup [:static-var :foo] [:a :b :c]] [[:integer 1] [:integer 2]] {}]
             "=> [foo.bar: 1 + 2, [abc: #a]]" [:call [:static-lookup [:static-var :foo] [:bar]] [[:binary-op :+ [:integer 1] [:integer 2]] [:call [:static-var :abc] [[:dynamic-var :a]] {}]] {}]}]

      (testing (str "expression: " source)
        (is (=  [:dawn expected]
                (dawn/parse parser source)))))
    
    (doseq [source [; Functions cannot be determined dynamically
                    "=> [#foo:]"
                    "=> [:foo.#bar:]"
                    "=> [foo.(var):]"]]
      (testing (str "Invalid source causes parse failure: " source)
        (is (insta/failure? (dawn/parse parser source)))))

    (testing "precedence matches bracketed expressions"
      (let [a "=> 1 + 2 * 3 - 4 and 5 + 2 * 3 ^ -2"
            b "=> ((1 + (2 * 3)) - 4) and (5 + (2 * (3 ^ (-2))))"]
        (is (= (dawn/parse parser a)
               (dawn/parse parser b)))))))
