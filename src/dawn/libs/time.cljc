(ns dawn.libs.time
  (:require [tick.alpha.api :as t]))

(defn since
  [context other]
  (let [now (get-in context [:static :time])
        duration (- other now)]
    {:days (int (/ duration  86400)) ; (* 60 60 24) = 86400
     :hours (int (/ duration 3600)) ; (* 60 60) = 3600
     :minutes (int (/ duration 60))
     :seconds duration}))

(defn -inst
  [time]
  (t/instant (* time 1000)))

(defn day-of-month
  [time]
  (-> time -inst t/day-of-month))

(defn day-of-week
  [time]
  (-> time -inst t/day-of-week str))

(defn hour-of-day
  [time]
  (-> time -inst t/hour))

(defn minute-of-hour
  [time]
  (-> time -inst t/minute))

(defn second-of-minute
  [time]
  (-> time -inst t/second))
