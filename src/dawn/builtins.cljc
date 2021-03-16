(ns dawn.builtins
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [dawn.utility :as util]
            [dawn.libs.core :as core-lib]
            [dawn.libs.math :as math-lib]
            [dawn.libs.list :as list-lib]
            [dawn.libs.trades :as trades-lib]
            [dawn.libs.time :as time-lib]))

; Data types for parameters:
; context -- only one parameter may be of type context and if present, must be first argument
; integer, float, text, boolean
; number -- integer or float
; list -- lists can contain anything
; list/integer, list/float, list/number, list/boolean, list/text -- list of specific types
; map -- values can be anything
; map/integer, map/float, map/number, map/boolean, map/text -- map with specific value types
; any -- parameter can be anything

(def libraries
  ; Core is always merged into context and always un-namespaced. Other libraries are merged in on demand and are namespaced.
  {:Core {:max     {:doc    {:text   "Return the maximum value of its inputs"
                             :params ["a" "b"]}
                    :params [:varargs]
                    :return :number
                    :fn     #(apply max %)}
          :min     {:doc    {:text   "Return the minimum value of its inputs"
                             :params ["a" "b"]}
                    :params [:varargs]
                    :return :number
                    :fn     #(apply min %)}
          :apply   {:doc    {:text   "Apply function to list of arguments"
                             :params ["function" "arguments"]}
                    :params [:context :any :list]
                    :return :any
                    :fn     core-lib/apply}
          :text    {:doc    {:text   "Join a list of variables together as text"
                             :params ["list"]}
                    :params [:varargs]
                    :return :text
                    :fn     #(apply str %)}
          :format  {:doc    {:text   "Join a list of variables together as formatted text"
                             :params ["format" "list"]}
                    :params [:text :varargs]
                    :return :text
                    :fn     #(apply format %1 %2)}
          :doctext {:doc    {:text   "Returns the documentation text for a function"
                             :params ["function"]}
                    :params [:context :any]
                    :return :text
                    :fn     core-lib/doctext}
          :value   {:doc    {:text "Returns the first non-nil value"
                             :params ["a", "b", "..."]}
                    :params [:varargs]
                    :return :any
                    :fn #(some identity %)}}
   :Math {:floor       {:doc    {:text   "Round float down to integer (towards negative infinity)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     math-lib/floor}
          :ceil        {:doc    {:text   "Round float up to integer (towards positive infinity)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     math-lib/ceil}
          :round       {:doc    {:text   "Round float to nearest integer (ties are rounded away from zero)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     #(int (+ % (if (pos? %) 0.5 -0.5)))}
          :truncate    {:doc    {:text   "Truncate float to integer (ignore decimal value)"
                                 :params ["value"]}
                        :params [:float]
                        :return :integer
                        :fn     int}
          :abs         {:doc    {:text   "Return absolute value"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     math-lib/abs}
          :sign        {:doc    {:text   "Returns 1 for positive numbers, -1 for negative and 0 for zero"
                                 :params ["value"]}
                        :params [:number]
                        :return :integer
                        :fn     #(cond (neg? %) -1 (pos? %) 1 :else 0)}
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
                        :fn     (complement zero?)}
          :is-even     {:doc    {:text   "Is the input value even"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     even?}
          :is-odd      {:doc    {:text   "Is the input value odd"
                                 :params ["value"]}
                        :params [:number]
                        :return :number
                        :fn     odd?}}
   :Text {:upper-case    {:doc    {:text   "Convert input text to all upper-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     string/upper-case}
          :lower-case    {:doc    {:text   "Convert input text to all lower-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     string/lower-case}
          :capitalize    {:doc    {:text   "Convert the first character of text to upper-case"
                                   :params ["text"]}
                          :params [:text]
                          :return :text
                          :fn     string/capitalize}
          :join          {:doc    {:text   "Join a list together into a single text item, sperated by 'seperator'"
                                   :params ["list" "seperator"]}
                          :params [:list :text]
                          :return :text
                          :fn     #(string/join %2 %1)}
          :slice         {:doc    {:text   "Extract a region of the input text"
                                   :params ["text" "first" "length"]}
                          :params [:text :integer :integer]
                          :return :text
                          :fn     (fn [text first length]
                                    (let [end (+ first length)]
                                      (if (> end (count text))
                                        (subs text first)
                                        (subs text first end))))}
          :split         {:doc    {:text   "Split text into a list of text, anywhere pattern is found"
                                   :params ["text" "pattern"]}
                          :params [:text :text]
                          :return :text
                          :fn     #(string/split %1 (re-pattern %2))}
          :find          {:doc    {:text   "Return the index of pattern in input text, or -1 if not found"
                                   :params ["text" "pattern"]}
                          :params [:text :text]
                          :return :integer
                          :fn     #(.indexOf %1 %2)}}
   :List {:new       {:doc    {:text "Retuern a new list containing the passed with elements"
                               :params ["a", "..."]}
                      :params [:varargs]
                      :return :list
                      :fn     #(into [] %)}
          :from      {:doc    {:text "Converts an input collection (list, set or table) into a list"
                               :params ["a"]}
                      :params [:any]
                      :return :list
                      :fn     vec}
          :find      {:doc    {:text   "Return the index of value in the list, or -1 if not found"
                               :params ["list" "value"]}
                      :params [:list :any]
                      :return :integer
                      :fn     #(.indexOf %1 %2)}
          :filled    {:doc    {:text   "Return a list of n elements filled with value"
                               :params ["n" "value"]}
                      :params [:integer :any]
                      :return :list
                      :fn     repeat}
          :append    {:doc    {:text   "Append value to the end of list"
                               :params ["list" "value"]}
                      :params [:list :any]
                      :return :list
                      :fn     #(conj %1 %2)}
          :concat    {:doc    {:text   "Concatenate two lists"
                               :params ["a" "b"]}
                      :params [:list :list]
                      :return :list
                      :fn     #(into %1 %2)}
          :take      {:doc    {:text   "Return the first n elements of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     #(vec (take %2 %1))}
          :drop      {:doc    {:text   "Return all but the first n elements of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     #(vec (drop %2 %1))}
          :first     {:doc    {:text   "Returun the first element of list"
                               :params ["list"]}
                      :params [:list]
                      :return :any
                      :fn     first}
          :second    {:doc    {:text "Return the second element of a list"
                               :params ["list"]}
                      :params [:list]
                      :return :any
                      :fn     second}
          :last      {:doc    {:text   "Return the last element of list"
                               :params ["list"]}
                      :params [:list]
                      :return :any
                      :fn     last}
          :get       {:doc    {:text   "Returns the n'th element of the list. If n is negative, count from the end"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :any
                      :fn     list-lib/get-element}
          :sum       {:doc    {:text   "Return the sum of all elements in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     #(reduce + %1)}
          :sort      {:doc    {:text   "Return the list with its elements sorted"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     sort}
          :reverse   {:doc    {:text   "Return the list with its elements reversed"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     reverse}
          :set       {:doc    {:text   "Return the list with element n set to value"
                               :params ["list" "n" "values"]}
                      :params [:list :integer :value]
                      :return :list
                      :fn     assoc}
          :set-in    {:doc    {:text   "Return the list with the nested element at successive indices in path to value"
                               :params ["list" "path" "value"]}
                      :params [:list :list :any]
                      :return :list
                      :fn     assoc-in}
          :transform {:doc    {:text   "Return the list with each value transformed by function"
                               :params ["list" "function"]}
                      :params [:context :list :any]
                      :return :list
                      :fn     list-lib/transform-elems}
          :keep      {:doc    {:text   "Return the list with only the elements for which function returns true"
                               :params ["list" "function"]}
                      :params [:context :list :any]
                      :return :list
                      :fn     list-lib/keep-elems}
          :remove    {:doc    {:text   "Return the list with the elements for which function returns true removed"
                               :params ["list" "function"]}
                      :params [:context :list :any]
                      :return :list
                      :fn     list-lib/remove-elems}
          :collect   {:doc    {:text   "Collect the values in list into a single returned value by calling function on the running collecton and each successive value"
                               :params ["list" "initial-collection-value" "function"]}
                      :params [:context :list :any :any]
                      :return :any
                      :fn     list-lib/collect-elems}
          :avg       {:doc    {:text   "Return the average (arithmetic mean) value of the elements in list"
                               :params ["list"]}
                      :params [:list/number]
                      :return :number
                      :fn     #(/ (reduce + %) (count %))}
          :take-last {:doc    {:text   "Return a list containing the last n items of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     #(vec (take-last %2 %1))}
          :drop-last {:doc    {:text   "Return a list containing all but the last n items of list"
                               :params ["list" "n"]}
                      :params [:list :integer]
                      :return :list
                      :fn     #(vec (drop-last %2 %1))}
          :range     {:doc    {:text   "Return a list containing the integers start...(end - 1)"
                               :params ["start" "end"]}
                      :params [:integer :integer]
                      :return :list/integer
                      :fn     #(vec (range %1 %2))}
          :indexed   {:doc    {:text   "Return an indexed list of [index, element] pairs for each element in list"
                               :params ["list"]}
                      :params [:list]
                      :return :list
                      :fn     #(vec (map-indexed vector %))}
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
                      :fn     empty?}
          :count     {:doc    {:text   "Rerurn a count of the number of elements in list"
                               :params ["list"]}
                      :params [:list]
                      :return :integer
                      :fn     count}
          :zip       {:doc {:text "Converts a map where all values are lists into a list of maps, where each map has one element taken from each list"
                            :params ["map"]}
                      :params [:map]
                      :return :map
                      :fn list-lib/zip}
          :unzip     {:doc {:text "Converts a list of maps into a map of lists, where each value in the input lists is inserted into the list with the matching key (opposite of zip)"
                            :params ["list"]}
                      :params [:list]
                      :return :map
                      :fn list-lib/unzip}}
   :Set  {:new          {:doc    {:text "Retuern a new set containing the passed with elements"
                                  :params ["a", "..."]}
                         :params [:varargs]
                         :return :list
                         :fn     #(into #{} %)}
          :from      {:doc    {:text "Converts an input collection (list, set or table) into a set"
                               :params ["a"]}
                      :params [:any]
                      :return :list
                      :fn     set}
          :union        {:doc    {:text   "Return the set union of two input lists (elements from both 'a' and 'b' without duplicates)"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     #(vec (sets/union (set %1) (set %2)))}
          :intersection {:doc    {:text   "Return the elements that are in both 'a' and 'b'"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     #(vec (sets/intersection (set %1) (set %2)))}
          :difference   {:doc    {:text   "Return the elements in 'a' that are not in 'b'"
                                  :params ["a" "b"]}
                         :params [:list :list]
                         :return :list
                         :fn     #(vec (sets/difference (set %1) (set %2)))}}
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
          :is-table   {:doc    {:text   "Return whether or not input is a table"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     map?}
          :is-nil     {:doc    {:text   "Returns whether or not input is nil"
                                :params ["input"]}
                       :params [:any]
                       :return :boolean
                       :fn     nil?}}
   :Time   {:since         {:doc {:text "Returns the number of seconds since a time"
                                  :params ["time"]}
                            :params [:context :integer]
                            :return :integer
                            :fn  time-lib/since}
            :day-of-month  {:doc {:text "Returns the hour of the day represented by a time"
                                  :params ["time"]}
                            :params [:integer]
                            :return :integer
                            :fn  time-lib/day-of-month}
            :day-of-week   {:doc {:text "Returns the day of the week represented by a time, as uppercase text (eg: 'TUESDAY')"
                                  :params ["time"]}
                            :params [:integer]
                            :return :integer
                            :fn  time-lib/day-of-week}
            :hour-of-day   {:doc {:text "Returns the hour of the day represented by a time"
                                  :params ["time"]}
                            :params [:integer]
                            :return :integer
                            :fn  time-lib/hour-of-day}
            :minute-of-hour {:doc {:text "Returns the minute of the hour of the day represented by a time"
                                   :params ["time"]}
                             :params [:integer]
                             :return :integer
                             :fn  time-lib/minute-of-hour}
            :second-of-minute {:doc {:text "Returns the second of the minute of the hour of the day represented by a time"
                                  :params ["time"]}
                            :params [:integer]
                            :return :integer
                            :fn  time-lib/second-of-minute}}
   :Trades {:max-contracts         {:doc    {:text   "Calculate maximum contracts possible with given balance at given price"
                                             :params ["balance" "price"]}
                                    :params [:context :float :float]
                                    :return :integer
                                    :fn     trades-lib/max-contracts}
            :price-offset          {:doc    {:text   "Calculate price at a percentage offset from price"
                                             :params ["percent" "price"]}
                                    :params [:float :float]
                                    :return :float
                                    :fn     trades-lib/price-offset}
            :risk-based-contracts  {:doc    {:text   "Calculate entry size based on stop price, balance, price and risk tolerance"
                                             :params ["balance" "price" "stop-price" "percentage-loss"]}
                                    :params [:float :float :float :float]
                                    :return :integer
                                    :fn     trades-lib/risk-based-contracts}
            :position-side         {:doc    {:text   "Returns the input value as a positive value if position side is long or negative if short"
                                             :params ["value"]}
                                    :params [:context :integer]
                                    :return :integer
                                    :fn     trades-lib/position-side}}})
