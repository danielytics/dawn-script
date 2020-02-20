(ns dawn.builtins
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+ try+]]
            [dawn.types :as types]
            [dawn.libs.core :as core-lib]
            [dawn.libs.math :as math-lib]
            [dawn.libs.text :as text-lib]
            [dawn.libs.list :as list-lib]
            [dawn.libs.set :as set-lib]
            [dawn.libs.trades :as trades-lib]))

; Data types for parameters:
; context -- only one parameter may be of type context and if present, must be first argument
; integer, float, text, boolean
; number -- integer or float
; list -- lists can contain anything
; list/integer, list/float, list/number, list/boolean, list/text -- list of specific types
; map -- values can be anything
; map/integer, map/float, map/number, map/boolean, map/text -- map with specific value types
; any -- parameter can be anything

(comment
  #?(:clj nil
     :cljs nil))

(def libraries
  ; Core is always merged into context and always un-namespaced. Other libraries are merged in on demand and are namespaced.
  {:Core {:max     {:doc    {:text   "Return the maximum value of its inputs"
                             :params ["a" "b"]}
                    :params [:number :number]
                    :return :number
                    :fn     max}
          :min     {:doc    {:text   "Return the minimum value of its inputs"
                             :params ["a" "b"]}
                    :params [:number :number]
                    :return :number
                    :fn     min}
          :apply   {:doc {:text "Apply function to list of arguments"
                          :params ["function" "arguments"]}
                    :params [:context :any :list]
                    :return :any
                    :fn core-lib/apply}
          :doctext {:doc    {:text   "Returns the documentation text for a function"
                             :params ["function"]}
                    :params [:context :any]
                    :return :text
                    :fn     core-lib/doctext}}
   :Math {:floor       {:doc    {:text   "Round float down to integer (towards negative infinity)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     nil}
          :ceil        {:doc    {:text   "Round float up to integer (towards positive infinity)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     nil}
          :round       {:doc    {:text   "Round float to nearest integer (ties are rounded towards posivite infinity)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     nil}
          :truncate    {:doc    {:text   "Truncate float to integer (ignore decimal value)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     nil}
          :abs         {:doc    {:text   "Return absolute value"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     math-lib/abs}
          :sign        {:doc    {:text   "Returns 1 for positive numbers, -1 for negative and 0 for zero"
                                 :params ["value"]}
                        :params [:number]
                        :return :integer
                        :fn     nil}
          :is-positive {:doc    {:text   "Is the input value positive"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     pos?}
          :is-negative {:doc    {:text   "Is the input value negative"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     neg?}
          :is-zero     {:doc    {:text   "Is the input value zero"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     zero?}
          :is-nonzero  {:doc    {:text   "Is the input value non-zero"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     (complement zero?)}}
   :Text {:upper-case    {:doc    {:text   "Convert input text to all upper-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     nil}
          :lower-case    {:doc    {:text   "Convert input text to all lower-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     nil}
          :capitalize    {:doc    {:text   "Convert the first character of text to upper-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     nil}
          :un-capitalize {:doc    {:text   "Convert the first character of text to lower-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     nil}
          :slice         {:doc    {:text   "Extract a region of the input text"
                                   :params ["text" "first" "length"]}
                          :params [:text :integer :integer]
                          :return :text
                          :fn     nil}
          :split         {:doc    {:text   "Split text into a list of text, anywhere pattern is found"
                                   :params ["text" "pattern"]}
                          :params [:text :text]
                          :return :text
                          :fn     nil}
          :find          {:doc    {:text   "Return the index of pattern in input text, or -1 if not found"
                                   :params ["text" "pattern"]}
                          :params [:text :text]
                          :return :integer
                          :fn     nil}}
   :List {:find      {:doc    {:text   "Return the index of value in the list, or -1 if not found"
                               :params ["list" "value"]}
                      :params [:list :any]
                      :return :integer
                      :fn     list-lib/find}
          :filled    {:doc    {:text   "Return a list of n elements filled with value"
                               :params ["n" "value"]}
                      :params [:integer :any]
                      :return :list
                      :fn     nil}
          :append    {:doc    {:text   "Append value to the end of list"
                               :params ["list" "value"]}
                      :params [:list :any]
                      :return :list
                      :fn     nil}
          :concat    {:doc    {:text   "Concatenate two lists"
                               :params ["a" "b"]}
                      :params [:list :list]
                      :return :list
                      :fn     nil}
          :take      {:doc    {:text   "Return the first n elements of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     nil}
          :drop      {:doc    {:text   "Return all but the first n elements of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     nil}
          :first     {:doc    {:text   "Returun the first element of list"
                               :params ["list"]}
                      :params [:list]
                      :return :any
                      :fn     nil}
          :last      {:doc    {:text   "Return the last element of list"
                               :params ["list"]}
                      :params [:list]
                      :return :any
                      :fn     nil}
          :sum       {:doc    {:text   "Return the sum of all elements in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     nil}
          :sort      {:doc    {:text   "Return the list with its elements sorted"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     nil}
          :reverse   {:doc    {:text   "Return the list with its elements reversed"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     nil}
          :set       {:doc    {:text   "Return the list with element n set to value"
                               :params ["list" "n" "values"]}
                      :params [:list :integer :value]
                      :return :list
                      :fn     nil}
          :set-in    {:doc    {:text   "Return the list with the nested element at successive indices in path to value"
                               :params ["list" "path" "value"]}
                      :params [:list :list :any]
                      :return :list
                      :fn     nil}
          :map       {:doc    {:text   "Return the list with function mapped over each value"
                               :params ["list" "function"]}
                      :params [:list :any]
                      :return :list
                      :fn     nil}
          :filter    {:doc    {:text   "Return the list with only the elements for which function returns true"
                               :params ["list" "function"]}
                      :params [:list :any]
                      :return :list
                      :fn     nil}
          :collect   {:doc    {:text   "Collect the values in list into a single returned value by calling function on the running collecton and each successive value"
                               :params ["list" "function" "initial-collection-value"]}
                      :params [:list :any :any]
                      :return :any
                      :fn     nil}
          :avg       {:doc    {:text   "Return the average (arithmetic mean) value of the elements in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     nil}
          :take-last {:doc    {:text   "Return a list containing the last n items of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     nil}
          :drop-last {:doc    {:text   "Return a list containing all but the last n items of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     nil}
          :range     {:doc    {:text   "Return a list containing the integers start...(end - 1)"
                               :params ["start" "end"]}
                      :params [:integer :integer]
                      :return :list/integer
                      :fn     nil}
          :indexed   {:doc    {:text   "Return an indexed list of [index, element] pairs for each element in list"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     nil}
          :max       {:doc    {:text   "Return the maximum value in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     #(reduce max %)}
          :min       {:doc    {:text   "Return the minimum value in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     #(reduce min %)}
          :is-empty  {:doc    {:text   "Return whether or not list is empty"
                               :params ["list"]}
                      :params [:list]
                      :return :boolean
                      :fn     nil}
          :count     {:doc    {:text   "Rerurn a count of the number of elements in list"
                               :params ["list"]}
                      :params [:list]
                      :return :integer
                      :fn     count}
          :reshape   {:doc    {:text   "Return a reshaped version of list, the shape is how many elements each dimension should have"
                               :params ["list" "shape"]}
                      :params [:list :list]
                      :return :list
                      :fn     nil}
          :shape     {:doc    {:text   "Return the shape of an input list, the shape is how many elementseach dimension has"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     nil}}
   :Set  {:union        {:doc    {:text   "Return the set union of two input lists"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     nil}
          :intersection {:doc    {:text   "Return the set intersection of two input lists"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     nil}
          :difference   {:doc    {:text   "Return the set difference of two input lists"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     nil}}
   :Type {:is-integer {:doc    {:text   "Return whether or not input is an integer"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     integer?}
          :is-float   {:doc    {:text   "Return whether or not input is a float"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     float?}
          :is-number  {:doc    {:text   "Return whether or not input is a number"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     number?}
          :is-boolean {:doc    {:text   "Return whether or not input is a boolean"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     boolean?}
          :is-text    {:doc    {:text   "Return whether or not input is text"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     string?}
          :is-list    {:doc    {:text   "Return whether or not input is a list"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     vector?}
          :is-map     {:doc    {:text   "Return whether or not input is a map"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     map?}}
   :Trades {:max-contracts {:doc    {:text   "Calculate maximum contracts possible with given balance at given price"
                                     :params ["balance" "price"]}
                            :params [:float :float]
                            :return :integer
                            :fn     trades-lib/max-contracts}
            :price-offset  {:doc    {:text   "Calculate price at a percentage offset from price"
                                     :params ["percent" "price"]}
                            :params [:float :float]
                            :return :float
                            :fn     trades-lib/price-offset}}})
